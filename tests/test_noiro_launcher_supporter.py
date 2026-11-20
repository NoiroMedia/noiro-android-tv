"""The real monotonic access lease rejects stale, unbounded and inactive grants."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SupporterLeaseTests(unittest.TestCase):
    def test_lease_boundaries_and_invalid_grants(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/SupporterLease.java'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'LeaseHarness.java'
            harness.write_text('''package tv.noiro.launcher;
public class LeaseHarness {
 static void check(boolean b) { if (!b) throw new AssertionError(); }
 public static void main(String[] a) {
  check(!SupporterLease.empty().valid(0));
  SupporterLease lease = SupporterLease.grant(true, 300, 1000);
  check(lease.valid(1000) && lease.valid(300999));
  check(!lease.valid(301000) && !lease.valid(-1));
  check(!SupporterLease.grant(false,300,1000).valid(1000));
  for (long seconds : new long[]{-1,0,301,Long.MAX_VALUE}) check(!SupporterLease.grant(true,seconds,1000).valid(1000));
  check(!SupporterLease.grant(true,300,Long.MAX_VALUE-1).valid(Long.MAX_VALUE-1));
  // Time spent waiting for the response does not extend authorization.
  check(!SupporterLease.grant(true,2,1000).valid(3000));
  check(SupporterLease.grant(true,2,1000).valid(2999));
  // A newly constructed lease has no authorization from a previous process.
  check(!SupporterLease.empty().valid(1000));
 }
}''')
            subprocess.run([str(java / 'javac'), '-d', tmp, str(source), str(harness)], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.LeaseHarness'], check=True)
