"""Native-test session contract. No credentials, schema creation or network here.
Call for EVERY new connection, including relational-test connections.
"""
SQL_MODE = 'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION,NO_ZERO_DATE,NO_ZERO_IN_DATE,ONLY_FULL_GROUP_BY'
REQUIRED_SQL_MODES = frozenset(SQL_MODE.split(','))
# Only data-constraint errors count as a successful negative constraint test.
# Timeouts, deadlocks, missing columns, server disconnects and syntax errors do not.
CONSTRAINT_ERROR_CODES = frozenset({1048, 1062, 1451, 1452, 3819})

class NativeConstraintViolation(Exception):
    def __init__(self, code):
        self.mysql_errno=code
        super().__init__('MYSQL_CONSTRAINT_'+str(code))

def initialize_session(connection):
    with connection.cursor() as cursor:
        cursor.execute("SET SESSION time_zone = '+00:00'")
        cursor.execute('SET SESSION sql_mode = %s',(SQL_MODE,))
        cursor.execute('SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ')
        cursor.execute('SET SESSION foreign_key_checks = 1')
        cursor.execute('SELECT @@session.time_zone,@@session.sql_mode,@@session.foreign_key_checks,@@session.transaction_isolation')
        row=cursor.fetchone()
    if not row or len(row)!=4 or row[0]!='+00:00' or not REQUIRED_SQL_MODES.issubset(set(row[1].split(','))) or row[2]!=1 or row[3]!='REPEATABLE-READ':
        raise RuntimeError('NATIVE_SESSION_PROFILE_MISMATCH')
    return {'time_zone':row[0],'sql_mode':row[1],'foreign_key_checks':row[2],'transaction_isolation':row[3]}
