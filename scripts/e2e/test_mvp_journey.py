"""Failure-path checks for the disposable runner; no external infrastructure needed."""
import contextlib
import io
import subprocess
import sys
import time
import traceback
import uuid
import unittest
from unittest.mock import Mock, patch

from mvp_journey import CommandFailure, Journey, command


class RunnerFailureTests(unittest.TestCase):
    def test_real_command_timeout_is_bounded_and_hides_sensitive_arguments(self):
        started = time.monotonic()
        sensitive = 'PRIVATE_' + uuid.uuid4().hex
        try:
            command(sys.executable, '-c', 'import time; time.sleep(10)', sensitive, timeout=.1)
        except CommandFailure as error:
            rendered = ''.join(traceback.format_exception(type(error), error, error.__traceback__))
            self.assertNotIn(sensitive, rendered)
            self.assertNotIn('TimeoutExpired', rendered)
            self.assertIn('timed out', str(error))
        else:
            self.fail('timeout must fail')
        self.assertLess(time.monotonic() - started, 3)

    def test_nonzero_exit_does_not_expose_arguments_or_output(self):
        with self.assertRaises(CommandFailure) as raised:
            command(sys.executable, '-c', "print('PRIVATE_OUTPUT'); raise SystemExit(1)", 'PRIVATE_ARGUMENT')
        self.assertNotIn('PRIVATE', str(raised.exception))

    def test_sql_waits_for_tcp_server_instead_of_initialization_socket(self):
        run = Journey.__new__(Journey)
        run.mysql, run.db_password = 'owned-mysql', 'private-password'
        with patch('mvp_journey.command', return_value='1') as execute:
            self.assertEqual('1', run.sql('mysql', 'SELECT 1'))
        args, kwargs = execute.call_args
        self.assertIn('--protocol=TCP', args)
        self.assertIn('-h127.0.0.1', args)
        self.assertEqual(10, kwargs['timeout'])

    def test_cleanup_attempts_every_container_and_closes_server_after_failures(self):
        run = Journey.__new__(Journey)
        process, handle = Mock(), Mock()
        process.poll.side_effect = OSError('private process error')
        process.wait.side_effect = subprocess.TimeoutExpired('java', 15)
        handle.close.side_effect = OSError('private log error')
        run.processes, run.handles = [process], [handle]
        run.containers, run.double = ['owned-first', 'owned-second'], Mock()
        output = io.StringIO()
        with patch('mvp_journey.command', side_effect=[CommandFailure('timeout'), 'removed']) as remove:
            with contextlib.redirect_stdout(output), self.assertRaises(CommandFailure) as raised:
                run.close()
        self.assertEqual(['owned-second', 'owned-first'], [c.args[-1] for c in remove.call_args_list])
        run.double.shutdown.assert_called_once()
        run.double.server_close.assert_called_once()
        process.kill.assert_called_once()
        self.assertIn('owned-second', output.getvalue())
        self.assertNotIn('private', str(raised.exception))

    def test_server_socket_closes_even_if_shutdown_fails(self):
        run = Journey.__new__(Journey)
        run.processes, run.handles, run.containers, run.double = [], [], [], Mock()
        run.double.shutdown.side_effect = OSError('shutdown failure')
        with self.assertRaises(OSError):
            run.close()
        run.double.server_close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
