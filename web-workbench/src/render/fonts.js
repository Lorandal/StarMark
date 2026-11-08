// SFNT metrics and cmap are read from the packaged font. Browser shaping uses
// a private FontFace for that exact byte hash and axis tuple, never system fonts.
import {registeredFont,hasFontRuntime} from './runtime.js';
const fontCache=new Map(),faceCache=new Map();
export function readFont(bytes) {
  const d=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),tables={},tag=at=>String.fromCharCode(...bytes.subarray(at,at+4));
  const u16=at=>d.getUint16(at),i16=at=>d.getInt16(at),u32=at=>d.getUint32(at),i32=at=>d.getInt32(at);
  for(let n=0;n<u16(4);n++){const at=12+n*16;tables[tag(at)]={offset:u32(at+8),length:u32(at+12)};}
  const unitsPerEm=u16(tables.head.offset+18),metric=tables['OS/2']?.offset,ascent=i16(metric===undefined?tables.hhea.offset+4:metric+68),descent=i16(metric===undefined?tables.hhea.offset+6:metric+70),axes={};
  if(tables.fvar){const at=tables.fvar.offset,offset=u16(at+4),stride=u16(at+10);for(let n=0;n<u16(at+8);n++){const p=at+offset+n*stride;axes[tag(p)]={min:i32(p+4)/65536,default:i32(p+8)/65536,max:i32(p+12)/65536};}}
  const cmap=tables.cmap.offset,cmaps=[];
  for(let n=0;n<u16(cmap+2);n++){const p=cmap+4+n*8,platform=u16(p),encoding=u16(p+2),offset=cmap+u32(p+4),format=u16(offset);if((platform===0||platform===3&&[1,10].includes(encoding))&&[4,12].includes(format))cmaps.push({offset,format});}
  cmaps.sort((a,b)=>b.format-a.format);
  function glyph(cp){for(const {offset:p,format}of cmaps){if(format===12){let lo=0,hi=u32(p+12)-1;while(lo<=hi){const mid=(lo+hi)>>1,at=p+16+mid*12,start=u32(at),end=u32(at+4);if(cp<start)hi=mid-1;else if(cp>end)lo=mid+1;else return u32(at+8)+cp-start;}}else if(cp<=65535){const n=u16(p+6)/2,ends=p+14,starts=ends+n*2+2,deltas=starts+n*2,ranges=deltas+n*2;for(let j=0;j<n;j++){if(cp>u16(ends+j*2))continue;if(cp<u16(starts+j*2))break;const range=u16(ranges+j*2),delta=i16(deltas+j*2);if(!range)return(cp+delta)&65535;const idx=u16(ranges+j*2+range+(cp-u16(starts+j*2))*2);return idx?(idx+delta)&65535:0;}}}return 0;}
  function metrics(values={}){
    const coordinates=Object.entries(axes).map(([name,a])=>{const v=values[name]??a.default;if(v<a.min||v>a.max)throw new Error('E_RESOURCE: 字体变轴超出范围');return v===a.default?0:v<a.default?(v-a.default)/(a.default-a.min):(v-a.default)/(a.max-a.default);});
    for(const name of Object.keys(values))if(!axes[name])throw new Error(`E_RESOURCE: 字库没有 ${name} 轴`);
    if(tables.avar){let at=tables.avar.offset+8;for(let axis=0;axis<coordinates.length;axis++){const count=u16(at);at+=2;const pairs=[];for(let i=0;i<count;i++,at+=4)pairs.push([i16(at)/16384,i16(at+2)/16384]);for(let i=1;i<pairs.length;i++)if(coordinates[axis]<=pairs[i][0]){const [a,b]=[pairs[i-1],pairs[i]],v=coordinates[axis];coordinates[axis]=a[1]+(v-a[0])/(b[0]-a[0])*(b[1]-a[1]);break;}}}
    if(!tables.MVAR)return {ascent,descent,unitsPerEm};
    const m=tables.MVAR.offset,count=u16(m+8),stride=u16(m+6),store=m+u16(m+10),regions=store+u32(store+2),axisCount=u16(regions),regionCount=u16(regions+2),scalars=[];
    for(let r=0;r<regionCount;r++){let scalar=1;for(let a=0;a<axisCount;a++){const at=regions+4+(r*axisCount+a)*6,start=i16(at)/16384,peak=i16(at+2)/16384,end=i16(at+4)/16384,v=coordinates[a];if(peak===0||start<0&&end>0||v===peak)continue;if(v<=start||v>=end){scalar=0;continue;}scalar*=v<peak?(v-start)/(peak-start):(end-v)/(end-peak);}scalars.push(scalar);}
    function delta(outer,inner){if(outer===65535&&inner===65535)return 0;const offset=u32(store+8+outer*4);if(!offset)return 0;const sub=store+offset,words=u16(sub+2),n=u16(sub+4),longCount=words&32767,longWords=!!(words&32768),rowBytes=(n+longCount)*(longWords?2:1);let at=sub+6+n*2+inner*rowBytes,sum=0;for(let i=0;i<n;i++){const size=i<longCount?(longWords?4:2):(longWords?2:1),v=size===4?i32(at):size===2?i16(at):d.getInt8(at);sum+=v*scalars[u16(sub+6+i*2)];at+=size;}return sum;}
    let up=ascent,down=descent;for(let i=0;i<count;i++){const at=m+12+i*stride,name=tag(at),v=delta(u16(at+4),u16(at+6));if(name==='hasc')up+=v;if(name==='hdsc')down+=v;}return{ascent:up,descent:down,unitsPerEm};
  }
  return {unitsPerEm,ascent,descent,axes,glyph,metrics};
}
function nonPrinting(cp){return cp===0x200c||cp===0x200d||cp>=0xfe00&&cp<=0xfe0f||cp>=0xe0100&&cp<=0xe01ef||cp>=0x202a&&cp<=0x202e||cp>=0x2066&&cp<=0x2069||cp===10||cp===13;}
export async function loadFonts(pkg,space) {
  const result={};
  for(const [id,definition]of Object.entries(space.fonts||{})){
    const resource=pkg.config.staruw.resources[definition.resourceId],bytes=pkg.files[resource?.path];
    if(!bytes)throw new Error(`E_RESOURCE: 缺少包内字体 ${id}`);
    const key=resource.sha256||resource.path;
    if(!fontCache.has(key))fontCache.set(key,readFont(bytes));
    const parsed=fontCache.get(key),axes=definition.axes||{};parsed.metrics(axes);
    const faceKey=key+JSON.stringify(axes);let record=faceCache.get(faceKey);
    if(!record){const family=`StarUW_${faceCache.size}_${key.slice(0,12)}`;record={family,promise:null};faceCache.set(faceKey,record);record.promise=(async()=>{if(hasFontRuntime())return registeredFont(bytes,family,axes);const descriptors={variationSettings:Object.entries(axes).map(([a,v])=>`"${a}" ${v}`).join(',')||'normal'};if(axes.wght)descriptors.weight=String(axes.wght);const face=new FontFace(family,bytes,descriptors);await face.load();globalThis.document?.fonts.add(face);globalThis.fonts?.add(face);return face;})();}
    await record.promise;
    result[id]={...parsed,...definition,axes,family:record.family};
  }
  function glyphFont(id,cp,seen=new Set()){
    if(seen.has(id))return null;seen.add(id);const font=result[id];if(!font)throw new Error(`E_RESOURCE: 字体未定义 ${id}`);
    if(nonPrinting(cp)||font.glyph(cp))return id;
    for(const fallback of font.fallbackFontIds||[]){const found=glyphFont(fallback,cp,seen);if(found)return found;}
    return null;
  }
  return {fonts:result,glyphFont};
}
export function fontRuns(text,fontId,fontResources){
  const runs=[];for(const char of text){const chosen=fontResources.glyphFont(fontId,char.codePointAt(0));if(!chosen)throw new Error(`E_RESOURCE: 包内字库缺少字形 U+${char.codePointAt(0).toString(16).toUpperCase()}`);if(runs.at(-1)?.fontId===chosen)runs.at(-1).text+=char;else runs.push({fontId:chosen,text:char});}return runs;
}
export function setFont(ctx,font,size,direction='ltr',letterSpacing=0){
  const weight=hasFontRuntime()?Math.round((font.axes?.wght||400)/100)*100:(font.axes?.wght||400);
  ctx.font=`${weight} ${size}px "${font.family}"`;ctx.textBaseline='alphabetic';ctx.textAlign='left';ctx.direction=direction;ctx.fontKerning='normal';
  if('fontVariationSettings'in ctx)ctx.fontVariationSettings=Object.entries(font.axes||{}).map(([tag,value])=>`"${tag}" ${value}`).join(',')||'normal';
  if('letterSpacing'in ctx)ctx.letterSpacing=`${letterSpacing}px`;
  else if(letterSpacing)throw new Error('E_CAPABILITY: 浏览器不支持 Canvas 字距，请使用新版 Chromium');
}
