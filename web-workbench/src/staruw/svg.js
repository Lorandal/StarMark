import {need, StaruwError, utf8} from './util.js';

const tags=new Set(['svg','g','defs','path','rect','circle','ellipse','line','polyline','polygon','linearGradient','radialGradient','stop','clipPath','title','desc','style']);
const styleProperties=new Set(['fill','stroke','stroke-width','stroke-linecap','stroke-linejoin','stroke-miterlimit','stroke-dasharray','stroke-dashoffset','fill-rule','clip-rule','opacity','fill-opacity','stroke-opacity','stop-color','stop-opacity','color','display','visibility','clip-path','transform','vector-effect','shape-rendering','paint-order','font-size']);
const entity=value=>value.replace(/&([^;]*);/g,(_,key)=>{
  const known={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'"};if(Object.hasOwn(known,key))return known[key];
  const number=/^#x[\da-f]+$/i.test(key)?parseInt(key.slice(2),16):/^#\d+$/.test(key)?Number(key.slice(1)):NaN;
  need(Number.isInteger(number)&&number>0&&number<=0x10ffff&&!(number>=0xd800&&number<=0xdfff),'E_RESOURCE','Invalid SVG entity');return String.fromCodePoint(number);
});

/** A bounded, presentation-only SVG grammar shared by Node and browsers.
 * Accepted text is retained verbatim; it is never "sanitized" into a different
 * graphic. Namespace, reference type and graph checks precede image decoding. */
export function validateSvg(text) {
  need(typeof text==='string'&&utf8.encode(text).length<=4*1024**2,'E_LIMIT','SVG exceeds complexity budget');
  need(!/<!DOCTYPE|<!ENTITY/i.test(text)&&!/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(text),'E_RESOURCE','Unsafe SVG declaration');
  const stack=[],nodes=[],ids=new Map(),references=[],graph=new Map();let at=0,root=null;
  const bad=message=>{throw new StaruwError('E_RESOURCE',message);};
  function reference(node,property,value) {
    let target=null,kind='gradient';
    if(property==='href') {need(['linearGradient','radialGradient'].includes(node.tag)&&/^#[^\s#()'"\\]+$/.test(value),'E_RESOURCE','Unsafe SVG href');target=value.slice(1);}
    else if(/url\s*\(/i.test(value)) {need(['fill','stroke','clip-path'].includes(property),'E_RESOURCE','Unsupported SVG reference property');const m=/^url\(\s*(?:"(#[^"\\]+)"|'(#[^'\\]+)'|(#[^\s'"()\\]+))\s*\)$/i.exec(value);need(m,'E_RESOURCE','SVG external URL is forbidden');target=(m[1]||m[2]||m[3]).slice(1);kind=property==='clip-path'?'clip':'gradient';}
    if(target)references.push({node,target,kind});
  }
  function declarations(node,source) {for(const declaration of source.split(';')){if(!declaration.trim())continue;const cut=declaration.indexOf(':');need(cut>0,'E_RESOURCE','Invalid SVG style');const key=declaration.slice(0,cut).trim().toLowerCase(),value=declaration.slice(cut+1).trim();need(styleProperties.has(key)&&!/[{}@\\]/.test(value)&&!/expression|(?:javascript|data|file|https?):|var\s*\(|attr\s*\(/i.test(value),'E_RESOURCE','Unsafe SVG style');reference(node,key,value);}}
  function content(source) {if(!source)return;need(!/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[\da-f]+);)/i.test(source),'E_RESOURCE','Unresolved SVG entity');source=entity(source);const current=stack.at(-1);if(current?.tag==='style') {need(!/[@\\]|url\s*\(|expression|(?:javascript|data|file|https?):/i.test(source),'E_RESOURCE','Unsafe SVG stylesheet');const rules=source.replace(/\/\*[\s\S]*?\*\//g,'').trim();let remainder=rules;const re=/([^{}]+)\{([^{}]*)\}/g;for(const match of rules.matchAll(re)){need(/^[\w\s.,#>*:+\-\[\]="'()]+$/.test(match[1]),'E_RESOURCE','Unsupported SVG selector');declarations(current,match[2]);remainder=remainder.replace(match[0],'');}need(!remainder.trim(),'E_RESOURCE','Malformed SVG stylesheet');}else need(!source.trim() || ['title','desc'].includes(current?.tag),'E_RESOURCE','Unsupported SVG text content');}
  while(at<text.length){
    const next=text.indexOf('<',at);if(next<0){content(text.slice(at));at=text.length;break;}content(text.slice(at,next));at=next;
    if(text.startsWith('<!--',at)){const end=text.indexOf('-->',at+4);need(end>=0&&!text.slice(at+4,end).includes('--'),'E_RESOURCE','Malformed SVG comment');at=end+3;continue;}
    if(text.startsWith('<?',at)){const end=text.indexOf('?>',at+2);need(end>=0&&!root&&/^<\?xml\s/i.test(text.slice(at,end)),'E_RESOURCE','Unsupported SVG processing instruction');at=end+2;continue;}
    if(text.startsWith('<![CDATA[',at)){const end=text.indexOf(']]>',at+9);need(end>=0&&stack.length,'E_RESOURCE','Malformed SVG CDATA');content(text.slice(at+9,end));at=end+3;continue;}
    const match=/^<\s*(\/?)\s*([A-Za-z_][\w.:-]*)/.exec(text.slice(at));need(match,'E_RESOURCE','Malformed SVG element');at+=match[0].length;const closing=Boolean(match[1]),tag=match[2];need(tags.has(tag),'E_RESOURCE','Unsupported or executable SVG element');
    if(closing){const end=/^\s*>/.exec(text.slice(at));need(end&&stack.at(-1)?.tag===tag,'E_RESOURCE','Unbalanced SVG tags');at+=end[0].length;stack.pop();continue;}
    const node={tag,attributes:Object.create(null),children:[]};let closed=false;
    while(at<text.length){const space=/^\s*/.exec(text.slice(at))[0];at+=space.length;if(text.startsWith('/>',at)){at+=2;closed=true;break;}if(text[at]==='>'){at++;break;}
      need(space.length>0,'E_RESOURCE','Unseparated SVG attribute');const attr=/^([A-Za-z_][\w.:-]*)\s*=\s*("[^"]*"|'[^']*')/.exec(text.slice(at));need(attr,'E_RESOURCE','Malformed SVG attribute');at+=attr[0].length;
      const key=attr[1],local=key.split(':').at(-1).toLowerCase(),raw=attr[2].slice(1,-1);need(!Object.hasOwn(node.attributes,key)&&!raw.includes('<')&&!/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[\da-f]+);)/i.test(raw),'E_RESOURCE','Duplicate or malformed SVG attribute');const value=entity(raw).trim();node.attributes[key]=value;
      need(!local.startsWith('on')&&!['src','base'].includes(local)&&!/[\\\x00-\x1f]/.test(value)&&!/@import|expression|javascript:|file:|data:|var\s*\(/i.test(value),'E_RESOURCE','Unsafe SVG attribute');
      if(key==='xmlns')need(value==='http://www.w3.org/2000/svg','E_RESOURCE','Invalid SVG namespace');else if(key==='xmlns:xlink')need(value==='http://www.w3.org/1999/xlink','E_RESOURCE','Invalid SVG link namespace');else {need(!key.includes(':')||['xlink:href','xml:space'].includes(key),'E_RESOURCE','Unknown SVG namespace');need(!/https?:|\/\//i.test(value),'E_RESOURCE','External SVG reference');if(local==='style')declarations(node,value);else reference(node,local,value);}
    }
    need(nodes.length<20000&&stack.length<64,'E_LIMIT','SVG node or depth budget exceeded');nodes.push(node);graph.set(node,node.children);if(stack.length)stack.at(-1).children.push(node);else {need(!root&&tag==='svg','E_RESOURCE','SVG must have one svg root');root=node;}
    if(node.attributes.id!==undefined){need(node.attributes.id&&!ids.has(node.attributes.id),'E_RESOURCE','Duplicate SVG ID');ids.set(node.attributes.id,node);}
    if(!closed)stack.push(node);
  }
  need(root&&stack.length===0,'E_RESOURCE','Incomplete SVG document');
  for(const {node,target,kind} of references){const dest=ids.get(target);need(dest&&(kind==='clip'?dest.tag==='clipPath':['linearGradient','radialGradient'].includes(dest.tag)),'E_RESOURCE','Missing or mistyped SVG fragment');graph.get(node).push(dest);}
  let edges=0;for(const list of graph.values())edges+=list.length;need(edges<=40000,'E_LIMIT','SVG edge budget exceeded');const active=new Set(),cache=new Map();
  function visit(node,depth){need(depth<=64&&!active.has(node),'E_RESOURCE','Cyclic or deep SVG reference');if(cache.has(node))return cache.get(node);active.add(node);let work=1,height=1;for(const child of graph.get(node)){const result=visit(child,depth+1);work+=result.work;height=Math.max(height,result.height+1);need(work<=100000&&height<=64,'E_LIMIT','SVG reference expansion exceeds budget');}active.delete(node);const result={work,height};cache.set(node,result);return result;}
  visit(root,1);
  const attr=root.attributes,viewBox=attr.viewBox?.trim().split(/[\s,]+/).map(Number);if(viewBox)need(viewBox.length===4&&viewBox.every(Number.isFinite)&&viewBox[2]>0&&viewBox[3]>0,'E_RESOURCE','Invalid SVG viewBox');
  const dimension=name=>{if(attr[name]===undefined)return null;const match=/^([\d.]+)(?:px)?$/.exec(attr[name]);if(!match)return null;const n=Number(match[1]);need(Number.isFinite(n)&&n>0,'E_RESOURCE','Invalid SVG size');return n;};
  const width=dimension('width')??viewBox?.[2]??300,height=dimension('height')??viewBox?.[3]??150;
  need(width<=8192&&height<=8192&&width*height<=16000000,'E_LIMIT','SVG dimensions exceed image budget');
  return {valid:true,width,height,nodes:nodes.length};
}
