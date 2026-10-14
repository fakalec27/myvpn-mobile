"""Read-only anchor validation against the currently generated pinned client."""
from pathlib import Path
import importlib.util

here = Path(__file__).resolve().parent
root = here.parents[1] / 'client-source'
spec = importlib.util.spec_from_file_location('diagnostics_patch', here / 'patch.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
written = {}
module.patch(root, lambda name, text: written.update({name: text}))
assert len(written) == 4
assert written['app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt'].count('DiagnosticsCollector.start') == 1
assert written['app/src/main/java/io/nekohasekai/sfa/compose/navigation/Navigation.kt'].count('composable("myvpn_diagnostics")') == 1
print('Diagnostics patch anchor validation: PASS (4 files, no writes)')
