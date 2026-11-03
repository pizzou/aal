#!/usr/bin/env python3
"""Cross-check literal frontend API paths against Java controller mappings.
Caveat: This is a static routing audit; it does not prove authorization, request
schemas, responses or database behavior. Run real integration/E2E tests as well.
"""
from pathlib import Path
import re,sys
root=Path(__file__).resolve().parents[1]
endpoints=set()
for f in root.glob('backend/src/main/java/**/*.java'):
 s=f.read_text()
 x=re.search(r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]+)"',s)
 prefix=x.group(1) if x else ''
 for m in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(\s*(?:value\s*=\s*)?\s*(?:"([^"]*)"|path\s*=\s*"([^"]*)"|\{\s*"([^"]*)"))?',s):
  p=m.group(2) or m.group(3) or m.group(4) or ''
  endpoint=re.sub(r'\{[^}]+\}','__PARAM__',(prefix.rstrip('/')+'/'+p.lstrip('/')).rstrip('/') or '/')
  endpoints.add(endpoint)
used=[]
for f in [*root.glob('frontend/lib/*.ts'),*root.glob('frontend/app/**/*.tsx'),*root.glob('frontend/components/*.tsx')]:
 s=f.read_text()
 for m in re.finditer(r'apiFetch(?:<[^\n]*?>)?\s*\(\s*([`\'\"])(/api/[^\1\n]*?)\1',s):
  val=m.group(2)
  # Dynamic URL search params (`/api/foo${query}`) do not alter the pathname.
  val=re.sub(r'\$\{(?:encodedQuery|asOf|status|note|query|group).*$' , '', val)
  val=re.sub(r'\$\{[^}]*\}','__PARAM__',val)
  route=val.split('?')[0].rstrip('/') or '/'
  used.append((route,str(f.relative_to(root)),s[:m.start()].count('\n')+1))
unmatched=[r for r in used if r[0] not in endpoints]
print(f'Controller route patterns: {len(endpoints)}; frontend API calls checked: {len(used)}; unmatched: {len(unmatched)}')
for route,file,line in unmatched:print(f'{file}:{line}: {route}')
sys.exit(bool(unmatched))
