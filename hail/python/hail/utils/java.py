import os
import sys
import re

import hail


class FatalError(Exception):
    """:class:`.FatalError` is an error thrown by Hail method failures"""


class Env:
    _jutils = None
    _hc = None
    _counter = 0
    _seed_generator = None

    @staticmethod
    def get_uid(base=None):
        if base:
            str_base = base
        else:
            str_base = ''
        Env._counter += 1
        return f"__uid_{str_base}{Env._counter}"

    @staticmethod
    def hail():
        return Env.spark_backend('Env.hail').hail_package()

    @staticmethod
    def jutils():
        return Env.spark_backend('Env.jutils').utils_package_object()

    @staticmethod
    def hc():
        if not Env._hc:
            import sys
            sys.stderr.write("Initializing Hail with default parameters...\n")

            backend_name = os.environ.get('HAIL_QUERY_BACKEND', 'spark')
            if backend_name == 'service':
                from hail.context import init_service
                init_service()
            elif backend_name == 'spark':
                from hail.context import init
                init()

        assert Env._hc is not None
        return Env._hc

    @staticmethod
    def backend():
        return Env.hc()._backend

    @staticmethod
    def spark_backend(op):
        from hail.backend.spark_backend import SparkBackend
        b = Env.backend()
        if isinstance(b, SparkBackend):
            return b
        else:
            raise NotImplementedError(
                f"{b.__class__.__name__} doesn't support {op}, only SparkBackend")

    @staticmethod
    def fs():
        return Env.backend().fs

    @staticmethod
    def spark_session():
        return Env.backend()._spark_session

    _dummy_table = None

    @staticmethod
    def dummy_table():
        if Env._dummy_table is None:
            import hail
            Env._dummy_table = hail.utils.range_table(1, 1).key_by().cache()
        return Env._dummy_table

    @staticmethod
    def set_seed(seed):
        Env._seed_generator = hail.utils.HailSeedGenerator(seed)

    @staticmethod
    def next_seed():
        if Env._seed_generator is None:
            Env.set_seed(None)
        return Env._seed_generator.next_seed()


def scala_object(jpackage, name):
    return getattr(getattr(jpackage, name + '$'), 'MODULE$')


def scala_package_object(jpackage):
    return scala_object(jpackage, 'package')


def jindexed_seq(x):
    return Env.jutils().arrayListToISeq(x)


def jindexed_seq_args(x):
    args = [x] if isinstance(x, str) else x
    return jindexed_seq(args)


def jiterable_to_list(it):
    if it is not None:
        return list(Env.jutils().iterableToArrayList(it))
    else:
        return None



_parsable_str = re.compile(r'[\w_]+')


def escape_parsable(s):
    if _parsable_str.fullmatch(s):
        return s
    else:
        return '`' + s.encode('unicode_escape').decode('utf-8').replace('`', '\\`') + '`'


def unescape_parsable(s):
    return bytes(s.replace('\\`', '`'), 'utf-8').decode('unicode_escape')


def jarray_to_list(a):
    return list(a) if a else None


class Log4jLogger:
    log_pkg = None

    @staticmethod
    def get():
        if Log4jLogger.log_pkg is None:
            Log4jLogger.log_pkg = Env.jutils()
        return Log4jLogger.log_pkg


def error(msg):
    Log4jLogger.get().error(msg)


def warn(msg):
    Log4jLogger.get().warn(msg)


def info(msg):
    Log4jLogger.get().info(msg)
