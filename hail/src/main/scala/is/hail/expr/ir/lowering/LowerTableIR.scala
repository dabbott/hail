package is.hail.expr.ir.lowering

import is.hail.expr.ir._
import is.hail.expr.types
import is.hail.expr.types.virtual._
import is.hail.methods.NPartitionsTable
import is.hail.rvd.{AbstractRVDSpec, RVDPartitioner}
import is.hail.utils._
import org.apache.spark.sql.Row

class LowererUnsupportedOperation(msg: String = null) extends Exception(msg)

case class ShuffledStage(child: TableStage)

case class Binding(name: String, value: IR)

abstract class TableStage(
  val letBindings: IndexedSeq[(String, IR)],
  val broadcastVals: Set[String],
  val globals: IR,
  val partitioner: RVDPartitioner,
  val contexts: IR) {

  def partition(ctxRef: Ref): IR

  def wrapInBindings(body: IR): IR = {
    letBindings.foldRight(body) { case ((name, binding), soFar) => Let(name, binding, soFar) }
  }

  def mapPartition(f: IR => IR): TableStage = {
    val outer = this
    new TableStage(letBindings, broadcastVals, globals, partitioner, contexts) {
      def partition(ctxRef: Ref): IR = f(outer.partition(ctxRef))
    }
  }

  def mapGlobals(f: IR => IR): TableStage = {
    val newGlobals = f(globals)
    val newID = genUID()
    val outer = this
    new TableStage(
      letBindings :+ newID -> newGlobals,
      broadcastVals + newID,
      Ref(newID, newGlobals.typ),
      partitioner, contexts) {
      def partition(ctxRef: Ref): IR = outer.partition(ctxRef)
    }
  }

  def collect(bind: Boolean = true): IR = {
    val ctx = Ref(genUID(), types.coerce[TStream](contexts.typ).elementType)
    val broadcastRefs = MakeStruct(letBindings.filter { case (name, _) => broadcastVals.contains(name) })
    val glob = Ref(genUID(), broadcastRefs.typ)
    val cda = CollectDistributedArray(contexts, broadcastRefs,
      ctx.name, glob.name,
      broadcastVals.foldLeft(partition(ctx))((accum, name) => Let(name, GetField(glob, name), accum)))
    if (bind) wrapInBindings(cda) else cda
  }
}

object LowerTableIR {
  def apply(ir: IR, typesToLower: DArrayLowering.Type, ctx: ExecuteContext): IR = {
    def lowerIR(ir: IR) = LowerIR.lower(ir, typesToLower, ctx)

    def lower(tir: TableIR): TableStage = {
      if (typesToLower == DArrayLowering.BMOnly)
        throw new LowererUnsupportedOperation("found TableIR in lowering; lowering only BlockMatrixIRs.")
      tir match {
        case TableRead(typ, dropRows, reader) =>
          val lowered = reader.lower(ctx, typ)
          val globalsID = genUID()

          if (dropRows) {
            new TableStage(
              FastIndexedSeq(globalsID -> lowered.globals),
              Set(globalsID),
              Ref(globalsID, lowered.globals.typ),
              RVDPartitioner.empty(typ.keyType),
              MakeStream(FastIndexedSeq(), TStream(TStruct.empty))) {
              def partition(ctxRef: Ref): IR = MakeStream(FastIndexedSeq(), TStream(typ.rowType))
            }
          } else {
            new TableStage(
              FastIndexedSeq(globalsID -> lowered.globals),
              Set(globalsID),
              Ref(globalsID, lowered.globals.typ),
              lowered.partitioner,
              lowered.contexts) {
              def partition(ctxRef: Ref): IR = lowered.body(ctxRef)
            }
          }


        case TableParallelize(rowsAndGlobal, nPartitions) =>
          val nPartitionsAdj = nPartitions.getOrElse(16)
          val loweredRowsAndGlobal = lowerIR(rowsAndGlobal)
          val loweredRowsAndGlobalRef = Ref(genUID(), loweredRowsAndGlobal.typ)

          val contextType = TStruct(
            "elements" -> TArray(GetField(loweredRowsAndGlobalRef, "rows").typ.asInstanceOf[TArray].elementType)
          )
          val numRows = ArrayLen(GetField(loweredRowsAndGlobalRef, "rows"))

          val numNonEmptyPartitions = If(numRows < nPartitionsAdj, numRows, nPartitionsAdj)
          val numNonEmptyPartitionsRef = Ref(genUID(), numNonEmptyPartitions.typ)

          val q = numRows floorDiv numNonEmptyPartitionsRef
          val qRef = Ref(genUID(), q.typ)

          val remainder = numRows - qRef * numNonEmptyPartitionsRef
          val remainderRef = Ref(genUID(), remainder.typ)

          val context = MakeStream((0 until nPartitionsAdj).map { partIdx =>
            val length = (numRows - partIdx + nPartitionsAdj - 1) floorDiv nPartitionsAdj

            val start = If(numNonEmptyPartitionsRef >= partIdx,
              If(remainderRef > 0,
                If(remainderRef < partIdx, qRef * partIdx + remainderRef, (qRef + 1) * partIdx),
                qRef * partIdx
              ),
              0
            )

            val elements = bindIR(start) { startRef =>
              ToArray(mapIR(rangeIR(startRef, startRef + length)) { elt =>
                ArrayRef(GetField(loweredRowsAndGlobalRef, "rows"), elt)
              })
            }
            MakeStruct(FastIndexedSeq("elements" -> elements))
          }, TStream(contextType))

          val globalsIR = GetField(loweredRowsAndGlobalRef, "global")
          val globalsRef = Ref(genUID(), globalsIR.typ)

          new TableStage(
            FastIndexedSeq[(String, IR)](
              (loweredRowsAndGlobalRef.name, loweredRowsAndGlobal),
              (globalsRef.name, globalsIR),
              (numNonEmptyPartitionsRef.name, numNonEmptyPartitions),
              (qRef.name, q),
              (remainderRef.name, remainder)
            ),
            Set(globalsRef.name),
            globalsRef,
            RVDPartitioner.unkeyed(nPartitionsAdj),
            context) {
            override def partition(ctxRef: Ref): IR = {
              ToStream(GetField(ctxRef, "elements"))
            }
          }

        case TableRange(n, nPartitions) =>
          val nPartitionsAdj = math.max(math.min(n, nPartitions), 1)
          val partCounts = partition(n, nPartitionsAdj)
          val partStarts = partCounts.scanLeft(0)(_ + _)

          val contextType = TStruct(
            "start" -> TInt32,
            "end" -> TInt32)

          val i = Ref(genUID(), TInt32)
          val ranges = Array.tabulate(nPartitionsAdj) { i => partStarts(i) -> partStarts(i + 1) }

          new TableStage(
            FastIndexedSeq.empty[(String, IR)],
            Set(),
            MakeStruct(FastSeq()),
            new RVDPartitioner(Array("idx"), tir.typ.rowType,
              ranges.map { case (start, end) =>
                Interval(Row(start), Row(end), includesStart = true, includesEnd = false)
              }),
            MakeStream(ranges.map { case (start, end) =>
              MakeStruct(FastIndexedSeq("start" -> start, "end" -> end)) },
              TStream(contextType))) {
            override def partition(ctxRef: Ref): IR = {
              StreamMap(StreamRange(
                GetField(ctxRef, "start"),
                GetField(ctxRef, "end"),
                I32(1)), i.name, MakeStruct(FastSeq("idx" -> i)))
            }
          }

        case TableMapGlobals(child, newGlobals) =>
          lower(child).mapGlobals(old => Let("global", old, newGlobals))

        case TableFilter(child, cond) =>
          val row = Ref(genUID(), child.typ.rowType)
          val loweredChild = lower(child)
          val env: Env[IR] = Env("row" -> row, "global" -> loweredChild.globals)
          loweredChild.mapPartition(rows => StreamFilter(rows, row.name, Subst(cond, BindingEnv(env))))

        case TableMapRows(child, newRow) =>
          if (ContainsScan(newRow))
            throw new LowererUnsupportedOperation(s"scans are not supported: \n${ Pretty(newRow) }")
          val loweredChild = lower(child)
          loweredChild.mapPartition(rows => mapIR(rows) { row =>
            val env: Env[IR] = Env("row" -> row, "global" -> loweredChild.globals)
            Subst(newRow, BindingEnv(env, scan = Some(env)))
          })

        case TableExplode(child, path) =>
          lower(child).mapPartition { rows =>
            flatMapIR(rows) { row: Ref =>
              val refs = Array.fill[Ref](path.length + 1)(null)
              val roots = Array.fill[IR](path.length)(null)
              var i = 0
              refs(0) = row
              while (i < path.length) {
                roots(i) = GetField(refs(i), path(i))
                refs(i + 1) = Ref(genUID(), roots(i).typ)
                i += 1
              }
              refs.tail.zip(roots).foldRight(
                mapIR(refs.last) { elt =>
                  path.zip(refs.init).foldRight[IR](elt) { case ((p, ref), inserted) =>
                    InsertFields(ref, FastSeq(p -> inserted))
                  }
                }) { case ((ref, root), accum) =>  Let(ref.name, root, accum) }
            }
          }

        case TableRename(child, rowMap, globalMap) =>
          val loweredChild = lower(child)
          val newGlobId = genUID()
          val newGlobals = CastRename(loweredChild.globals, loweredChild.globals.typ.asInstanceOf[TStruct].rename(globalMap))
          new TableStage(
            loweredChild.letBindings :+ newGlobId -> newGlobals,
            loweredChild.broadcastVals + newGlobId,
            Ref(newGlobId, newGlobals.typ),
            loweredChild.partitioner.copy(kType = loweredChild.partitioner.kType.rename(rowMap)),
            loweredChild.contexts
          ) {
            override def partition(ctxRef: Ref): IR = {
              val oldPartition = loweredChild.partition(ctxRef)
              mapIR(oldPartition) { row =>
                CastRename(row, row.typ.asInstanceOf[TStruct].rename(rowMap))
              }
            }
          }

        case node =>
          throw new LowererUnsupportedOperation(s"undefined: \n${ Pretty(node) }")
      }
    }

    ir match {
      case TableCount(tableIR) =>
        invoke("sum", TInt64,
          lower(tableIR).mapPartition(rows => Cast(ArrayLen(ToArray(rows)), TInt64)).collect())

      case TableGetGlobals(child) =>
        val stage = lower(child)
        stage.wrapInBindings(stage.globals)

      case TableCollect(child) =>
        val lowered = lower(child).mapPartition(ToArray)
        lowered.wrapInBindings(
          MakeStruct(FastIndexedSeq(
            "rows" -> ToArray(flatMapIR(ToStream(lowered.collect(bind = false))) { elt => ToStream(elt) }),
            "global" -> lowered.globals)))

      case TableToValueApply(child, NPartitionsTable()) =>
        val lowered = lower(child)
        ArrayLen(ToArray(lowered.contexts))

      case node if node.children.exists(_.isInstanceOf[TableIR]) =>
        throw new LowererUnsupportedOperation(s"IR nodes with TableIR children must be defined explicitly: \n${ Pretty(node) }")
    }
  }
}
