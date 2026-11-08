import {LIMITS,FORMAT,StaruwError,need,bytes,parseJson,jsonBytes,sha256,treeDigest,clone,own,checkPaths,compareUtf8,equalBytes} from './util.js';
import {inspectZip,openZip,makeZip} from './archive.js';
import {schema} from './schema.js';
import {validateSemantics} from './semantics.js';
import {inspectRendererSupport,SUPPORTED_CAPABILITIES} from '../render/support.js';
export {validateSvg} from './svg.js';
export {StaruwError,LIMITS,sha256,treeDigest,parseJson} from './util.js';

const originals=new WeakMap();
const diagnostic=(code,message,path='',severity='warning')=>({code,severity,path,message});
const isZip=(data,path)=>/\.(?:zip|staruw)$/i.test(path)||data.length>=4&&data[0]===80&&data[1]===75&&([3,5,7].includes(data[2])&&[4,6,8].includes(data[3]));

function normalizedRoot(names) {
  if(names.includes('config.json')||names.includes('bundle.json'))return '';
  const roots=new Set(names.map(name=>name.split('/')[0]));
  if(roots.size===1&&names.every(name=>name.includes('/')))return `${[...roots][0]}/`;
  return '';
}
function stripRoot(files,prefix){return Object.fromEntries(Object.entries(files).map(([name,data])=>[prefix?name.slice(prefix.length):name,data]));}
function checkFileBudgets(files) {
  const names=Object.keys(files);need(names.length>0&&names.length<=LIMITS.entries,'E_LIMIT','Package file count exceeds budget');checkPaths(names.map(path=>({path})));
  let total=0;for(const path of names){const data=bytes(files[path]);total+=data.length;need(data.length<=LIMITS.file&&total<=LIMITS.expanded,'E_LIMIT','Package expanded size exceeds budget',path);need(!isZip(data,path),'E_ZIP','Nested archive is not a template resource',path);if(/\.json$/i.test(path))need(data.length<=LIMITS.json,'E_LIMIT','JSON exceeds size budget',path);}
  return total;
}

export async function sealPackageFiles(input) {
  const files=Object.create(null);for(const [path,data] of Object.entries(input))if(path!=='manifest.json')files[path]=bytes(data);
  const records=[];for(const path of Object.keys(files).sort(compareUtf8))records.push({path,size:files[path].length,sha256:await sha256(files[path])});
  const manifest={format:'com.realize.starmark.watermark-manifest',manifestVersion:1,files:records,payloadDigest:await treeDigest(files)};
  files['manifest.json']=jsonBytes(manifest);return files;
}

async function validateFiles(files,options={}) {
  checkFileBudgets(files);need(own(files,'config.json'),'E_FORMAT','Archive does not contain one root template');const config=parseJson(files['config.json'],'config.json');
  need(config&&typeof config==='object'&&config.format===FORMAT&&config.staruw&&typeof config.staruw==='object','E_FORMAT','This file is not a StarUW package');
  const u=config.staruw;need(u.standard==='StarUW'&&typeof u.protocolVersion==='string'&&/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(u.protocolVersion),'E_VERSION','Invalid protocol marker or version');
  // A future protocol is only an opaque local backup. Do not interpret its
  // manifest, resources, capabilities or renderer under today's schema.
  if(u.protocolVersion!=='1.0.0')return {config,manifest:null,valid:true,mode:'preserve',editMode:'preserve',readOnly:true,renderStatus:'unsupported',missingCapabilities:[],warnings:[diagnostic('E_VERSION','This protocol version can only be retained as its original ZIP.','/staruw/protocolVersion','error')],payloadDigest:'',protocolVersion:u.protocolVersion,engine:'unknown'};
  for(const [path,data] of Object.entries(files))if(/\.json$/i.test(path))parseJson(data,path);
  need(own(files,'manifest.json'),'E_RESOURCE','manifest.json is missing');const manifest=schema('manifest',parseJson(files['manifest.json'],'manifest.json'),'manifest.json'),seen=new Set();
  for(const row of manifest.files){need(row.path!=='manifest.json'&&!seen.has(row.path)&&own(files,row.path),'E_HASH','Manifest is duplicated or references a missing file',row.path);seen.add(row.path);need(files[row.path].length===row.size&&await sha256(files[row.path])===row.sha256,'E_HASH','Manifest file length or digest mismatch',row.path);}
  need(seen.size===Object.keys(files).length-1&&Object.keys(files).every(path=>path==='manifest.json'||seen.has(path)),'E_HASH','Manifest must cover every regular file');
  const payload=Object.fromEntries(Object.entries(files).filter(([path])=>path!=='manifest.json'));need(await treeDigest(payload)===manifest.payloadDigest,'E_HASH','Payload digest mismatch','manifest.json');
  schema('config',config,'config.json');const semantic=await validateSemantics(config,files),support=inspectRendererSupport(config);
  const supported=options.supportedCapabilities?new Set(options.supportedCapabilities):SUPPORTED_CAPABILITIES;
  const missing=[...new Set([...u.requires.filter(capability=>!supported.has(capability)),...semantic.unsupported.map(x=>x.capability),...support.errors.map(x=>x.capability??'renderer.unsupported')])].sort();
  const warnings=[...semantic.warnings,...semantic.unsupported,...support.warnings.map(item=>({severity:'warning',path:'',...item})),...support.errors.map(item=>({severity:'error',path:'',...item}))];
  for(const capability of missing)if(!warnings.some(x=>x.capability===capability))warnings.push({...diagnostic('E_CAPABILITY','Required capability is unavailable; only original ZIP export is allowed.','/staruw/requires','error'),capability});
  const simpleScene=u.render.engine==='scene'&&(u.authoring?.profile==='authoring.ten-elements.v1'||u.scene.nodes.length<=10&&u.scene.nodes.every(n=>n.type!=='group'));
  const mode=missing.length?'preserve':u.render.engine==='xiaomi'?'controlled':simpleScene?'full':'controlled';
  return {config,manifest,valid:true,mode,editMode:mode,readOnly:mode==='preserve',warnings,diagnostics:warnings,missingCapabilities:missing,renderStatus:missing.length?'unsupported':warnings.some(x=>x.code==='W_MATERIAL_FALLBACK')?'fallback':'supported',payloadDigest:manifest.payloadDigest,identity:clone(u.identity),protocolVersion:u.protocolVersion,engine:u.render.engine};
}

async function importSingle(input,options={}) {
  const archive=Array.isArray(input?.entries)?input:inspectZip(input),names=archive.entries.filter(x=>!x.directory).map(x=>x.path),prefix=normalizedRoot(names),{files:raw,expanded}=openZip(archive),files=stripRoot(raw,prefix);
  need(own(files,'config.json'),'E_FORMAT','Ambiguous template root or unsupported archive');
  // Source/config.json is retained provenance, never a competing template root.
  for(const path of Object.keys(files))if(path!=='config.json'&&/(?:^|\/)config\.json$/.test(path)&&!path.startsWith('source/')){
    const extra=parseJson(files[path],path);need(extra?.format!==FORMAT,'E_FORMAT','Archive contains multiple template roots',path);
  }
  const report=await validateFiles(files,options),pkg={config:report.config,manifest:report.manifest,files,originalBytes:archive.data.slice(),readOnly:report.readOnly,warnings:report.warnings,mode:report.mode,report};
  const fileHashes=Object.create(null);for(const [path,data] of Object.entries(files))fileHashes[path]=await sha256(data);
  originals.set(pkg,{config:JSON.stringify(pkg.config),manifest:pkg.manifest,report,fileHashes,archiveHash:await sha256(pkg.originalBytes)});
  return {pkg,expanded};
}

/** Import a single ZIP or collection. Bad children of a valid collection are
 * isolated in result.diagnostics; structural outer/archive failures throw. */
export async function importStaruw(input,options={}) {
  const supplied=bytes(input);need(supplied.length<=LIMITS.bundleZip,'E_LIMIT','Archive exceeds compressed input budget');
  const archive=inspectZip(supplied.slice()),names=archive.entries.filter(x=>!x.directory).map(x=>x.path),prefix=normalizedRoot(names),relative=names.map(x=>prefix?x.slice(prefix.length):x);
  const bundle=relative.includes('bundle.json')||!relative.includes('config.json')&&relative.length>0&&relative.every(p=>/\.zip$/i.test(p));
  if(!bundle){const result=[(await importSingle(archive,options)).pkg];result.diagnostics=[];result.bundle=false;return result;}
  need(!relative.includes('config.json'),'E_FORMAT','Archive mixes templates and a bundle');const {files:raw}=openZip(archive,{bundle:true}),files=stripRoot(raw,prefix);let rows;
  if(own(files,'bundle.json')){
    const manifest=schema('bundle',parseJson(files['bundle.json'],'bundle.json',LIMITS.bundleManifest),'bundle.json');rows=manifest.packages;const listed=new Set(rows.map(x=>x.path));need(listed.size===rows.length&&Object.keys(files).length===listed.size+1&&Object.keys(files).every(p=>p==='bundle.json'||listed.has(p)),'E_FORMAT','Bundle manifest coverage is ambiguous');
    for(const row of rows)need(/\.zip$/i.test(row.path)&&own(files,row.path),'E_FORMAT','Bundle children must be independent ZIPs',row.path);
  }else rows=Object.keys(files).map(path=>({path,size:files[path].length,sha256:null}));
  need(rows.length>0&&rows.length<=LIMITS.bundlePackages,'E_LIMIT','Bundle contains too many packages');
  const accepted=[],diagnostics=[],digests=new Set(),identities=new Map();let expanded=0;
  for(const row of rows){
    try{
      const child=files[row.path];need(child.length===row.size&&(!row.sha256||await sha256(child)===row.sha256),'E_HASH','Bundle child bytes do not match manifest',row.path);
      const inspected=inspectZip(child);expanded+=inspected.entries.reduce((n,e)=>n+e.expanded,0);need(expanded<=LIMITS.bundleInner,'E_LIMIT','Cumulative inner bundle size exceeds budget');
      const imported=await importSingle(inspected,options),pkg=imported.pkg,digest=pkg.manifest?.payloadDigest,id=pkg.config.staruw.identity,key=id?`${id.id}/${id.revision}`:null;
      if(key&&identities.has(key)&&identities.get(key)!==digest)throw new StaruwError('E_ID_CONFLICT','Same template ID and revision have different payloads',row.path);
      if(digest&&digests.has(digest)){diagnostics.push(diagnostic('W_DUPLICATE','Duplicate template payload skipped.',row.path));continue;}
      if(digest)digests.add(digest);if(key)identities.set(key,digest);accepted.push(pkg);
    }catch(error){if(error.code==='E_LIMIT'&&expanded>LIMITS.bundleInner)throw error;diagnostics.push(error instanceof StaruwError?{...error.toDiagnostic(),path:row.path}:diagnostic('E_ZIP','Invalid bundle child',row.path,'error'));}
  }
  accepted.diagnostics=diagnostics;accepted.bundle=true;return accepted;
}

/** Validate editable in-memory design by rebuilding the manifest in a temporary
 * snapshot. Resource hashes are NOT silently repaired by this validation API. */
export async function validatePackage(pkg,options={}) {
  need(pkg&&pkg.config&&pkg.files,'E_FORMAT','Package has no config or files');const files={...pkg.files,'config.json':jsonBytes(pkg.config)};
  const report=await validateFiles(await sealPackageFiles(files),options);return report;
}

async function baseline(pkg,options) {
  const cached=originals.get(pkg);if(cached)return cached;
  if(pkg.originalBytes){const original=(await importSingle(bytes(pkg.originalBytes),options)).pkg,result=originals.get(original);originals.set(pkg,result);return result;}
  if(pkg.builtIn){
    need(pkg.baselineConfig&&pkg.baselineFiles,'E_ID_CONFLICT','Built-in source snapshot is missing; reopen the original preset before exporting');
    const sourceConfig=clone(pkg.baselineConfig),sourceFiles=Object.fromEntries(Object.entries(pkg.baselineFiles).map(([path,data])=>[path,bytes(data).slice()]));
    sourceFiles['config.json']=jsonBytes(sourceConfig);const sealed=await sealPackageFiles(sourceFiles),report=await validateFiles(sealed,options),result={config:JSON.stringify(sourceConfig),manifest:report.manifest,report,builtIn:true};
    originals.set(pkg,result);return result;
  }
  return null;
}
async function isUnchanged(config,files,original){const names=Object.keys(files);if(JSON.stringify(config)!==original.config||names.length!==Object.keys(original.fileHashes).length)return false;for(const path of names)if(!own(original.fileHashes,path)||await sha256(files[path])!==original.fileHashes[path])return false;return true;}

/** Export only protocol configuration and declared package files. App/runtime
 * state, photos, EXIF overrides, selection and undo history are never read. */
export async function exportStaruw(pkg,options={}) {
  need(pkg&&pkg.config&&pkg.files,'E_FORMAT','Package has no template configuration');
  const config=clone(pkg.config),files=Object.fromEntries(Object.entries(pkg.files).map(([p,b])=>[p,bytes(b).slice()])),originalBytes=pkg.originalBytes?bytes(pkg.originalBytes).slice():null;
  const original=await baseline(pkg,options);
  if(original&&!original.builtIn)need(originalBytes&&await sha256(originalBytes)===original.archiveHash,'E_HASH','Original ZIP bytes changed after verification');
  if(options.original){need(originalBytes,'E_FORMAT','This package has no original ZIP');return originalBytes;}
  if(original&&!original.builtIn&&!pkg.dirty&&!options.previewBytes&&await isUnchanged(config,files,original))return originalBytes;
  if(original?.report.readOnly||pkg.readOnly)throw new StaruwError('E_CAPABILITY','This package can only be downloaded as its original ZIP');
  need(config.staruw?.protocolVersion==='1.0.0','E_VERSION','Only StarUW 1.0.0 can be edited and exported');
  // Editable scene packages have no OEM top-level private runtime state.
  for(const key of ['photo','photoBytes','photoMetadata','photoOverrides','runtime','ui','editorState','undo','history','selectedNode','absolutePath'])need(!own(config,key),'E_SCOPE','Runtime or private application state cannot be exported',`/${key}`);
  const u=config.staruw;
  if(options.previewBytes){const preview=bytes(options.previewBytes);let resourceId=Object.keys(u.resources).find(id=>u.resources[id].path===u.metadata.preview);const path='preview.png';if(!resourceId){resourceId='preview';while(own(u.resources,resourceId))resourceId+='Image';}
    // Keep retained OEM previews/source bytes untouched when creating an edited
    // synthetic preview. The old file remains covered by the manifest.
    const old=u.resources[resourceId],sourcePath=u.source?.files.some(row=>row.path===path);need(!sourcePath,'E_SCOPE','Synthetic preview would overwrite preserved OEM source');
    if(old&&old.path!==path&&u.source?.files.some(row=>row.path===old.path)){resourceId='editedPreview';while(own(u.resources,resourceId))resourceId+='Image';}
    files[path]=preview.slice();u.resources[resourceId]={path,kind:'image',mediaType:'image/png',sha256:await sha256(preview)};u.metadata.preview=path;u.metadata.previewKind='rendered';
  }
  if(original){const previous=JSON.parse(original.config).staruw.identity;
    if(options.revisionMode==='update'&&!original.builtIn){const floor=options.revisionFloor??0;need(Number.isInteger(floor)&&floor>=0&&floor<=2147483647,'E_ID_CONFLICT','Invalid revision floor');const revision=Math.max(previous.revision,floor)+1;need(revision<=2147483647,'E_ID_CONFLICT','Revision range exhausted; derive a new identity');u.identity={...previous,revision};}
    else {need(!options.revisionMode||['derive','update'].includes(options.revisionMode),'E_BINDING','Unknown revision mode');u.identity={id:crypto.randomUUID(),revision:1,derivedFrom:{id:previous.id,revision:previous.revision,payloadDigest:original.manifest.payloadDigest}};}
  }
  for(const r of Object.values(u.resources)){need(own(files,r.path),'E_RESOURCE','Resource is missing during export',r.path);r.sha256=await sha256(files[r.path]);}
  files['config.json']=jsonBytes(config);const sealed=await sealPackageFiles(files),report=await validateFiles(sealed,options);need(!report.readOnly,'E_CAPABILITY','Edited template requires unsupported capabilities');const output=makeZip(sealed);need(output.length<=LIMITS.zip,'E_LIMIT','Export exceeds compressed package budget');
  const verified=await importSingle(output,options);need(verified.pkg.manifest.payloadDigest===report.payloadDigest,'E_HASH','Export verification mismatch');return output;
}

export async function exportBundle(packages,options={}) {
  need(Array.isArray(packages)&&packages.length>0&&packages.length<=LIMITS.bundlePackages,'E_LIMIT','Bundle must contain one to one hundred templates');const files=Object.create(null),rows=[];let innerExpanded=0,outerExpanded=0;
  for(let i=0;i<packages.length;i++){const data=await exportStaruw(packages[i],options),path=`watermark-${String(i+1).padStart(3,'0')}.staruw.zip`;innerExpanded+=inspectZip(data).entries.reduce((n,e)=>n+e.expanded,0);outerExpanded+=data.length;need(innerExpanded<=LIMITS.bundleInner&&outerExpanded<=LIMITS.bundleExpanded,'E_LIMIT','Bundle size exceeds budget');files[path]=data;rows.push({path,size:data.length,sha256:await sha256(data)});}
  const manifest={format:'com.realize.starmark.watermark-bundle',bundleVersion:'1.0.0',standard:'StarUW',packages:rows,...(options.name?{name:options.name}:{})};schema('bundle',manifest);files['bundle.json']=jsonBytes(manifest);need(files['bundle.json'].length<=LIMITS.bundleManifest,'E_LIMIT','Bundle manifest exceeds budget');const output=makeZip(files);need(output.length<=LIMITS.bundleZip,'E_LIMIT','Bundle exceeds compressed budget');const check=await importStaruw(output);need(check.length===packages.length&&check.diagnostics.length===0,'E_HASH','Bundle self-verification failed');return output;
}
