"""Exercise the commit-message policy without creating commits or changing Git identities.

Run with Python 3. A mock `git var` supplies adversarial identity fixtures only to
the hook process; this never writes repository/global Git configuration.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / 'scripts/git-hooks/commit-msg'


class CommitIdentityPolicyTest(unittest.TestCase):
    def test_identity_and_message_policy(self):
        shell = shutil.which('sh')
        if not shell and os.name == 'nt':
            candidate = Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Git/usr/bin/sh.exe'
            if candidate.is_file():
                shell = str(candidate)
        self.assertIsNotNone(shell, 'Git/POSIX sh is required to exercise the hook')
        approved = 'Ziqiang Huang <hzq.fox@gmail.com>'
        other_approved = 'Ziqiang Huang <ziqiang.huang@embl.de>'
        cases = [
            ('approved', approved, approved, 'Correct the Fourier mask\n', True),
            ('both_addresses', other_approved, approved,
             'Update\n\nSigned-off-by: Ziqiang Huang <ziqiang.huang@embl.de>\n', True),
            ('wrong_author_name', 'Other Person <hzq.fox@gmail.com>', approved, 'Update\n', False),
            ('wrong_committer_name', approved, 'Other Person <hzq.fox@gmail.com>', 'Update\n', False),
            ('wrong_author_email', 'Ziqiang Huang <other@example.org>', approved, 'Update\n', False),
            ('wrong_committer_email', approved, 'Ziqiang Huang <other@example.org>', 'Update\n', False),
            ('other_trailer', approved, approved, 'Update\n\nCo-authored-by: Other Person <other@example.org>\n', False),
            ('spoofed_trailer_name', approved, approved, 'Update\n\nSigned-off-by: Other Person <hzq.fox@gmail.com>\n', False),
            ('indented_trailer', approved, approved, 'Update\n\n  Reviewed-by: Other Person <other@example.org>\n', False),
            ('malformed_trailer', approved, approved, 'Update\n\nSuggested-by: Someone\n', False),
            ('generated_line', approved, approved, 'Update\n\nGenerated with a tool\n', False),
            ('generated_whitespace', approved, approved, 'Update\n\nGenerated\tby a tool\n', False),
            ('several_credits', approved, approved,
             'Update\n\nSigned-off-by: Ziqiang Huang <hzq.fox@gmail.com>\nReviewed-by: Someone <other@example.org>\n', False),
        ]
        with tempfile.TemporaryDirectory(prefix='opm-hook-test-') as folder:
            temp = Path(folder)
            message = temp / 'message.txt'
            # Defining a shell function lets us test the actual hook file, including its
            # subprocess pipelines, without impersonating anyone in Git or making commits.
            driver = '''git() {
    case "$2" in
        GIT_AUTHOR_IDENT) printf '%s 0 +0000\\n' "$OPM_TEST_AUTHOR" ;;
        GIT_COMMITTER_IDENT) printf '%s 0 +0000\\n' "$OPM_TEST_COMMITTER" ;;
        *) return 2 ;;
    esac
}
set -- "$OPM_TEST_MESSAGE"
. "$OPM_TEST_HOOK"
'''
            for label, author, committer, text, expected in cases:
                with self.subTest(case=label):
                    message.write_text(text, encoding='utf-8')
                    env = os.environ.copy()
                    env['PATH'] = str(Path(shell).parent) + os.pathsep + env.get('PATH', '')
                    env.update(OPM_TEST_AUTHOR=author, OPM_TEST_COMMITTER=committer,
                               OPM_TEST_MESSAGE=message.as_posix(), OPM_TEST_HOOK=HOOK.as_posix())
                    result = subprocess.run([shell, '-c', driver], env=env, text=True,
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                    self.assertEqual(result.returncode == 0, expected, result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
