#!/usr/bin/env node
/* Static navigation and button wiring inventory, for pre-deployment review.
 * This proves code wiring and route existence, not browser or API behavior. */
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../frontend/node_modules/typescript');
const root = path.resolve(__dirname, '..');
const app = path.join(root, 'frontend/app');
const list = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? list(path.join(d,e.name)) : [path.join(d,e.name)]);
const pages = list(app).filter(f => f.endsWith('/page.tsx')).map(f => '/' + path.relative(app, path.dirname(f)).split(path.sep).join('/')).map(x=>x==='/'?'/':x);
const components = [...list(app), ...list(path.join(root,'frontend/components'))].filter(f => /\.(tsx|ts)$/.test(f));
const routes = new Set(pages);
const invalid = [], dynamic = [], actions = [], unresolvedButtons = [], warnings=[];
const interactionCounts = {buttons:0, handlerButtons:0, formSubmitButtons:0, linkElements:0, forms:0};
const getAttr = (node, key) => node.attributes?.properties?.find(p=>ts.isJsxAttribute(p) && p.name.text===key);
const attrText = a=>{if(!a?.initializer)return null; if(ts.isStringLiteral(a.initializer))return a.initializer.text;if(ts.isJsxExpression(a.initializer)&&a.initializer.expression){let v=a.initializer.expression;if(ts.isStringLiteral(v)||ts.isNoSubstitutionTemplateLiteral(v))return v.text;if(ts.isTemplateExpression(v))return v.head.text+'${dynamic}'+v.templateSpans.map(x=>x.literal.text).join('${dynamic}');}return null;};
function existsRoute(p){const clean=p.split(/[?#]/,1)[0].replace(/\/+/g,'/').replace(/\$\{dynamic\}/g,'DYNAMIC');if(clean==='/') return true;
 if(routes.has(clean))return true;
 return pages.some(page=>{const a=page.split('/').filter(Boolean),b=clean.split('/').filter(Boolean);return a.length===b.length && a.every((segment,i)=>segment.startsWith('[') && segment.endsWith(']') || segment===b[i]);});}
for(const f of components){let src=fs.readFileSync(f,'utf8'), sf=ts.createSourceFile(f,src,ts.ScriptTarget.Latest,true,f.endsWith('.tsx')?ts.ScriptKind.TSX:ts.ScriptKind.TS);const rel=path.relative(root,f).replaceAll('\\','/');
 function route(text,where,node){if(!text||!text.startsWith('/')) return; if(/^\/api\//.test(text)) return;
 const normalized=text.replace(/\$\{dynamic\}/g,'DYNAMIC');const out={file:rel,line:sf.getLineAndCharacterOfPosition(node.getStart(sf)).line+1,target:normalized,where};
 if(existsRoute(normalized)) actions.push(out);else if(text.includes('DYNAMIC') || normalized.includes('DYNAMIC'))dynamic.push(out);else invalid.push(out);
 }
 function hasFormAncestor(n){let p=n.parent;while(p){if(ts.isJsxElement(p)&&p.openingElement.tagName.getText(sf)==='form')return Boolean(getAttr(p.openingElement,'onSubmit'));p=p.parent;}return false;}
 function walk(n){if(ts.isJsxOpeningElement(n)||ts.isJsxSelfClosingElement(n)){const name=n.tagName.getText(sf);const h=getAttr(n,'href');if(h){interactionCounts.linkElements++;route(attrText(h),'href',n);}
 if(name==='form')interactionCounts.forms++;
 if(name==='button'){interactionCounts.buttons++;const t=getAttr(n,'type');const c=getAttr(n,'onClick');const type=attrText(t);if(c)interactionCounts.handlerButtons++;else if(type==='submit'||hasFormAncestor(n))interactionCounts.formSubmitButtons++; if(!c && type!=='submit' && !hasFormAncestor(n)){ const text=n.parent?.getText(sf).replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').slice(0,90); unresolvedButtons.push({file:rel,line:sf.getLineAndCharacterOfPosition(n.getStart(sf)).line+1,text,type:type||'default'});}}
 }
 if(ts.isCallExpression(n)){const expr=n.expression.getText(sf);if(/(?:router|location|window\.location)\.(?:push|replace|assign)\b/.test(expr) && n.arguments.length){const v=n.arguments[0];let val=null;if(ts.isStringLiteral(v)||ts.isNoSubstitutionTemplateLiteral(v))val=v.text;else if(ts.isTemplateExpression(v))val=v.head.text+'${dynamic}'+v.templateSpans.map(x=>x.literal.text).join('${dynamic}');if(val)route(val,expr,n);}}
 ts.forEachChild(n,walk);} walk(sf);
}
console.log(JSON.stringify({scannedFiles:components.length,definedRoutes:pages.length,linkedRoutes:actions.length,interactions:interactionCounts,unresolvedRoutes:invalid,unknownDynamicRoutes:dynamic,buttonCandidates:unresolvedButtons},null,2));
if(invalid.length || unresolvedButtons.length) process.exitCode=1;
