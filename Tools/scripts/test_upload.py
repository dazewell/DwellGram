"""Unit tests for upload.py caption assembly and trailing build hashtag."""
import asyncio
import html
import json
import os
import sys
import types
import unittest
from pathlib import Path

_THIS_DIR = os.path.dirname(__file__)


def _install_pyrogram_stub():
    if "pyrogram" in sys.modules:
        return
    pyrogram = types.ModuleType("pyrogram")
    pyrogram.Client = object

    errors = types.ModuleType("pyrogram.errors")

    class FloodWait(Exception):
        pass

    class FloodPremiumWait(Exception):
        pass

    errors.FloodWait = FloodWait
    errors.FloodPremiumWait = FloodPremiumWait

    types_mod = types.ModuleType("pyrogram.types")

    class InputMediaDocument:
        def __init__(self, media=None):
            self.media = media
            self.caption = None

    types_mod.InputMediaDocument = InputMediaDocument

    enums = types.ModuleType("pyrogram.enums")

    class ParseMode:
        DISABLED = "disabled"

    enums.ParseMode = ParseMode

    sys.modules["pyrogram"] = pyrogram
    sys.modules["pyrogram.enums"] = enums
    sys.modules["pyrogram.errors"] = errors
    sys.modules["pyrogram.types"] = types_mod


_install_pyrogram_stub()
sys.path.insert(0, _THIS_DIR)
import upload  # noqa: E402


class UploadCaptionTests(unittest.TestCase):
    def setUp(self):
        self._env_backup = dict(os.environ)
        for key in (
            "COMMIT_ID", "COMMIT_URL", "COMMIT_MESSAGE", "BRANCH", "BRANCH_URL",
            "PR_NUMBER", "PR_TITLE", "PR_URL", "AI_SUMMARY",
        ):
            os.environ.pop(key, None)
        os.environ["COMMIT_ID"] = "abc123def"
        os.environ["COMMIT_MESSAGE"] = "fix workflow routing #infra"

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env_backup)

    def _set_build_type(self, value):
        upload.build_type = value

    def test_test_build_uses_beaker_and_test_hashtag(self):
        self._set_build_type("test")
        os.environ["PR_NUMBER"] = "316"
        os.environ["PR_TITLE"] = "update staging resolver"
        caption = upload.get_caption()
        self.assertIn("🧪", caption)
        self.assertNotIn("🚀", caption)
        self.assertTrue(upload.get_hashtag().endswith("#test"))

    def test_staging_build_uses_rocket_and_rc_hashtag(self):
        self._set_build_type("staging")
        caption = upload.get_caption()
        self.assertIn("🚀", caption)
        self.assertTrue(upload.get_hashtag().endswith("#rc"))

    def test_unknown_build_type_falls_back_to_staging_symbols(self):
        self._set_build_type("unexpected")
        caption = upload.get_caption()
        self.assertIn("🚀", caption)
        self.assertTrue(upload.get_hashtag().endswith("#rc"))

    def test_hashtag_survives_long_commit_and_generated_summary(self):
        self._set_build_type("test")
        os.environ["PR_NUMBER"] = "316"
        os.environ["PR_TITLE"] = "x" * 500
        os.environ["COMMIT_MESSAGE"] = "line one\n" + ("very long commit body " * 200)
        os.environ["AI_SUMMARY"] = "<b>✨ Features</b>\n\n" + "\n".join(
            f"• change {i} with extra descriptive filler text" for i in range(60)
        )
        caption = self._build_caption()
        self.assertLessEqual(upload.tg_len(caption), 1024)
        self.assertTrue(caption.rstrip().endswith("#test"), caption[-80:])
        self.assertTrue(caption.endswith("\n\n#test"), caption[-20:])

    def test_hashtag_survives_pathologically_long_staging_caption(self):
        self._set_build_type("staging")
        os.environ["COMMIT_MESSAGE"] = "y" * 5000
        os.environ["AI_SUMMARY"] = "z" * 5000
        caption = self._build_caption()
        self.assertLessEqual(upload.tg_len(caption), 1024)
        self.assertTrue(caption.rstrip().endswith("#rc"), caption[-80:])
        self.assertTrue(caption.endswith("\n\n#rc"), caption[-20:])

    def test_hashtag_survives_html_heavy_commit_content(self):
        self._set_build_type("test")
        os.environ["PR_NUMBER"] = "316"
        os.environ["PR_TITLE"] = "a <script>alert(1)</script> & <b>title</b>" * 20
        os.environ["COMMIT_MESSAGE"] = "<weird> & </weird>" * 200
        caption = self._build_caption()
        self.assertLessEqual(upload.tg_len(caption), 1024)
        self.assertTrue(caption.rstrip().endswith("#test"), caption[-80:])
        self.assertNotRegex(caption, r"&[a-zA-Z]*$")

    def test_no_pr_uses_commit_subject_fallback(self):
        self._set_build_type("staging")
        os.environ["COMMIT_MESSAGE"] = "a plain dev push\n\nbody text"
        caption = upload.get_caption()
        self.assertIn("a plain dev push", caption)

    def _build_caption(self):
        # Mirrors get_document() budget logic without requiring APK files.
        limit = 1024
        hashtag = upload.get_hashtag()
        overhead = upload.tg_len(upload.get_caption(commit_msg_budget=0)) + upload.tg_len(hashtag)
        content_budget = max(0, limit - overhead)
        escaped_commit_message = html.escape(upload.get_commit_info()[2])
        msg_reserve = min(upload.tg_len(escaped_commit_message), content_budget // 2)
        generated_summary = upload.get_ai_summary(max_inner=max(0, content_budget - msg_reserve))
        room = limit - overhead - upload.tg_len(generated_summary)
        base_caption = upload.get_caption(commit_msg_budget=max(0, room))
        return base_caption + generated_summary + hashtag


class SelfUpdateTests(unittest.TestCase):
    def setUp(self):
        self._env_backup = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self._env_backup)

    def test_update_chat_id_accepts_bare_and_prefixed_ids(self):
        for raw in ("1234567890", "-1001234567890", " 1234567890 "):
            os.environ["UPDATE_CHANNEL_ID"] = raw
            self.assertEqual(upload.get_update_chat_id(), -1001234567890)

    def test_update_chat_id_rejects_placeholders(self):
        for raw in ("", "0", "-", "abc", "-100"):
            os.environ["UPDATE_CHANNEL_ID"] = raw
            self.assertIsNone(upload.get_update_chat_id())

    def test_metadata_round_trips_after_the_tag(self):
        text = upload.get_update_metadata("updateOfficial", "13.1.1-abc1234", 1266, 1700000000, 41, 42)
        self.assertTrue(text.startswith("#updateOfficial "))
        payload = json.loads(text[len("#updateOfficial"):].strip())
        self.assertEqual(payload["document"], {"arm64-v8a": 41})
        self.assertEqual(payload["message"], 42)
        self.assertEqual(payload["version_code"], 1266)
        self.assertEqual(payload["build_timestamp"], 1700000000)
        self.assertFalse(payload["can_not_skip"])

    def test_app_version_matches_the_repo_build_files(self):
        os.environ["COMMIT_ID"] = "abc123def456"
        root = Path(_THIS_DIR).parent.parent
        version = upload.get_app_version(root)
        self.assertIsNotNone(version)
        self.assertTrue(version[0].endswith("-abc123d"))
        self.assertGreater(version[1], 0)

    def _message(self, mid, name):
        return types.SimpleNamespace(id=mid, document=types.SimpleNamespace(file_name=name))

    def test_variants_match_by_file_name(self):
        messages = [
            self._message(10, "DwellGram-Official-abc1234-v13.1.1(1266)-arm64-v8a.apk"),
            self._message(11, "DwellGramX-Unofficial-abc1234-v13.1.1(1266)-arm64-v8a.apk"),
        ]
        matched = upload.match_variants(messages)
        self.assertEqual(matched["Official"].id, 10)
        self.assertEqual(matched["Unofficial"].id, 11)

    def test_swapped_album_is_refused(self):
        messages = [
            self._message(10, "DwellGramX-Unofficial-abc1234-v13.1.1(1266)-arm64-v8a.apk"),
            self._message(11, "DwellGram-Official-abc1234-v13.1.1(1266)-arm64-v8a.apk"),
        ]
        with self.assertRaises(ValueError):
            upload.match_variants(messages)

    def test_test_builds_publish_nothing(self):
        upload.build_type = "test"
        os.environ["UPDATE_CHANNEL_ID"] = "1234567890"

        class NoClient:
            def __getattr__(self, name):
                raise AssertionError(f"client.{name} called for a test build")

        asyncio.run(upload.publish_update(NoClient(), []))


if __name__ == "__main__":
    unittest.main()
