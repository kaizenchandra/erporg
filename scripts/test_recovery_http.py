"""Verifier retry contracts: never retry arbitrary mutations or hide failed checks."""
import io
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError
import restore_application_check as recovery


class RecoveryHttpTests(unittest.TestCase):
    def error(self, code=429, delay="2"):
        return HTTPError("http://localhost/test", code, "Test response", {"Retry-After": delay}, io.BytesIO())

    def response(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = b'{"status":"UP"}'
        return response

    @patch.object(recovery.time, "sleep")
    def test_read_honours_retry_after_then_returns_actual_response(self, sleep):
        opener = MagicMock()
        opener.open.side_effect = [self.error(), self.response()]
        self.assertEqual((200, {"status": "UP"}), recovery.request(opener, "/read"))
        sleep.assert_called_once_with(2)
        self.assertEqual(2, opener.open.call_count)

    @patch.object(recovery.time, "sleep")
    def test_mutation_is_not_retried(self, sleep):
        opener = MagicMock()
        opener.open.side_effect = self.error()
        self.assertEqual((429, None), recovery.request(opener, "/write", b"{}"))
        sleep.assert_not_called()
        self.assertEqual(1, opener.open.call_count)

    @patch.object(recovery.time, "sleep")
    def test_retry_is_bounded_and_a_second_rate_limit_still_fails(self, sleep):
        opener = MagicMock()
        opener.open.side_effect = [self.error(delay="1000"), self.error()]
        self.assertEqual((429, None), recovery.request(opener, "/read"))
        sleep.assert_called_once_with(60)
        self.assertEqual(2, opener.open.call_count)

    @patch.object(recovery.time, "sleep")
    def test_permission_denial_is_not_retried(self, sleep):
        opener = MagicMock()
        opener.open.side_effect = self.error(code=403)
        self.assertEqual((403, None), recovery.request(opener, "/private"))
        sleep.assert_not_called()
