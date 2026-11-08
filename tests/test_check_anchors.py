"""TEST-TOOLS-ANCHORS-234: constructors and probe errors must be reported accurately."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools import check_anchors


class AnchorParserTest(unittest.TestCase):
    def test_constructor_and_native_method(self):
        out = ("  public                     <init>(Lhost/Page;)V\n"
               "  public static final        access$setData(Lhost/Data;)V\n"
               "  private                    setTabViews(Ljava/util/List;)V\n")
        self.assertEqual(set(check_anchors.METHOD_RE.findall(out)),
                         {"<init>", "access$setData", "setTabViews"})

    def test_requested_count_and_missing_method(self):
        result = subprocess.CompletedProcess([], 0, "  public <init>()V\n", "")
        with patch.object(check_anchors.subprocess, "run", return_value=result):
            report = check_anchors.check(Path("probe.py"), Path("base.apk"),
                                         [{"class": "host.Page", "methods": ["<init>", "render"]}])
        self.assertEqual(report["checked"], 2)
        self.assertFalse(report["ok"])
        self.assertEqual(report["anchors"][0]["missing"], ["render"])

    def test_failed_probe_cannot_pass_class_only_anchor(self):
        result = subprocess.CompletedProcess([], 1, "", "probe error")
        with patch.object(check_anchors.subprocess, "run", return_value=result):
            report = check_anchors.check(Path("probe.py"), Path("base.apk"),
                                         [{"class": "host.Page", "methods": []}])
        self.assertFalse(report["ok"])
        self.assertIn("exit 1", report["anchors"][0]["reason"])


if __name__ == "__main__":
    unittest.main()
