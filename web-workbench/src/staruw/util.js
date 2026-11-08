export const LIMITS = Object.freeze({ zip:64*1024**2, expanded:128*1024**2, file:32*1024**2, json:4*1024**2, entries:512, bundleZip:1024**3, bundleExpanded:1024**3, bundleInner:2*1024**3, bundleManifest:1024**2, bundlePackages:100 });
export const FORMAT = 'com.realize.starmark.watermark';
export class StaruwError extends Error {
  constructor(code, message, path='', details={}) { super(`${code}: ${message}`); this.name='StaruwError'; this.code=code; this.path=path; Object.assign(this,details); }
  toDiagnostic() { return {code:this.code,severity:'error',path:this.path,message:this.message,...(this.capability?{capability:this.capability}:{})}; }
}
export function need(condition, code, message, path='') { if (!condition) throw new StaruwError(code,message,path); }
export function bytes(value) { if (value instanceof Uint8Array) return new Uint8Array(value.buffer,value.byteOffset,value.byteLength); if (value instanceof ArrayBuffer) return new Uint8Array(value); if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer,value.byteOffset,value.byteLength); throw new StaruwError('E_FORMAT','Expected archive bytes'); }
export const utf8 = new TextEncoder();
export function decode(value, path='') { try { return new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(value); } catch { throw new StaruwError('E_SCHEMA','Invalid UTF-8',path); } }
export function stringifyJson(value) {
  const active=new Set();let tokens=0;
  function visit(v,depth){need(depth<=256&&++tokens<=300000,'E_LIMIT','JSON complexity budget exceeded');if(v===null||typeof v==='string'||typeof v==='boolean')return;
    if(typeof v==='number'){need(Number.isFinite(v),'E_SCHEMA','Non-finite JSON number');return;}
    need(v&&typeof v==='object'&&!active.has(v)&&(Array.isArray(v)||Object.getPrototypeOf(v)===Object.prototype||Object.getPrototypeOf(v)===null),'E_SCHEMA','Only acyclic JSON data may be exported');active.add(v);for(const child of Object.values(v))visit(child,depth+1);active.delete(v);
  }visit(value,0);return JSON.stringify(value);
}
export const jsonBytes = value => utf8.encode(stringifyJson(value));
export function safePath(path, directory=false) {
  need(typeof path==='string' && path.length>0 && [...path].length<=512 && path===path.normalize('NFC'),'E_PATH','Invalid or non-NFC archive path');
  need(!/[\\\x00-\x1f\x7f-\x9f:<>"|?*]/u.test(path),'E_PATH','Forbidden archive path character',path);
  const name=directory && path.endsWith('/')?path.slice(0,-1):path;
  const parts=name.split('/');
  need(parts.length<=9,'E_LIMIT','Directory nesting exceeds eight levels',path);
  for (const part of parts) need(part && part!=='.' && part!=='..' && !/[. ]$/.test(part) && !/^(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\..*)?$/i.test(part),'E_PATH','Unsafe archive path segment',path);
  return name;
}
// Upper/lower normalization conservatively includes multi-character Unicode case
// folds (ß/ligatures/final sigma) rather than admitting portable-path collisions.
export const foldPath = path => path.toUpperCase().toLowerCase().normalize('NFC');
export function checkPaths(entries) {
  const seen=new Map();
  for (const {path,directory=false} of entries) { const normalized=safePath(path,directory),folded=foldPath(normalized); need(!seen.has(folded),'E_PATH','Duplicate or case-folding path collision',path); seen.set(folded,{directory,path}); }
  for (const [path] of seen) { const parts=path.split('/'); for(let i=1;i<parts.length;i++) need(!seen.has(parts.slice(0,i).join('/')) || seen.get(parts.slice(0,i).join('/')).directory,'E_PATH','File and directory prefix collision',path); }
}
export async function sha256(value) { const b=bytes(value); need(globalThis.crypto?.subtle,'E_CAPABILITY','Web Crypto requires a secure browser context'); return [...new Uint8Array(await crypto.subtle.digest('SHA-256',b))].map(x=>x.toString(16).padStart(2,'0')).join(''); }
export function compareUtf8(a,b) { const x=utf8.encode(a),y=utf8.encode(b); for(let i=0;i<Math.min(x.length,y.length);i++) if(x[i]!==y[i]) return x[i]-y[i]; return x.length-y.length; }
export async function treeDigest(files) { let records=''; for(const path of Object.keys(files).sort(compareUtf8)) records+=`${path}\0${files[path].length}\0${await sha256(files[path])}\n`; return sha256(utf8.encode(records)); }
export function equalBytes(a,b) { return a?.length===b?.length && a.every((x,i)=>x===b[i]); }
export const clone = value => JSON.parse(stringifyJson(value));
export const own = (object,key) => Object.prototype.hasOwnProperty.call(object,key);

/** Bounded JSON parser which retains order and rejects duplicate keys before
 * JSON.parse can discard them. Untrusted JSON never passes through a reviver. */
export function parseJson(input,path='',limit=LIMITS.json) {
  const inputBytes=bytes(input); need(inputBytes.length<=limit,'E_LIMIT','JSON exceeds size budget',path);
  const text=decode(inputBytes,path); let at=0,tokens=0;
  need(text[0]!=='\ufeff','E_SCHEMA','JSON BOM is forbidden',path);
  const fail=()=>{throw new StaruwError('E_SCHEMA','Malformed JSON, duplicate key, or non-finite number',path);};
  const space=()=>{while(/[\t\n\r ]/.test(text[at]??'\0'))at++;};
  function string() { const start=at++; while(at<text.length) { const c=text[at++]; if(c==='"') {try{return JSON.parse(text.slice(start,at));}catch{fail();}} if(c==='\\')at++; } fail(); }
  function value(depth=0) {
    if(depth>256 || ++tokens>300000) throw new StaruwError('E_LIMIT','JSON complexity budget exceeded',path);
    space(); const c=text[at];
    if(c==='"')return string();
    if(c==='{' || c==='[') { at++;space();const object=c==='{'?{}:[],end=c==='{'?'}':']',keys=new Set(); if(text[at]===end){at++;return object;}
      while(at<text.length) {space();let key;if(c==='{'){if(text[at]!=='"')fail();key=string();if(keys.has(key))fail();keys.add(key);space();if(text[at++]!==':')fail();}
        const child=value(depth+1); if(c==='{')Object.defineProperty(object,key,{value:child,enumerable:true,writable:true,configurable:true});else object.push(child);
        space(); const sep=text[at++];if(sep===end)return object;if(sep!==',')fail();
      }fail();
    }
    for(const [token,v] of [['true',true],['false',false],['null',null]]) if(text.startsWith(token,at)){at+=token.length;return v;}
    const match=/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(text.slice(at)); if(!match)fail();at+=match[0].length;const n=Number(match[0]);if(!Number.isFinite(n))fail();return n;
  }
  const parsed=value();space();if(at!==text.length)fail();return parsed;
}
