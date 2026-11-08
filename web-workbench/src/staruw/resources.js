import {need,decode,parseJson} from './util.js';
import {crc32} from './archive.js';
import {validateSvg} from './svg.js';

export function inspectFont(data,path='') {
  const view=new DataView(data.buffer,data.byteOffset,data.byteLength),tables=new Map();
  const u16=p=>{need(p>=0&&p+2<=data.length,'E_RESOURCE','Truncated font',path);return view.getUint16(p);};
  const u32=p=>{need(p>=0&&p+4<=data.length,'E_RESOURCE','Truncated font',path);return view.getUint32(p);};
  const tag=p=>String.fromCharCode(...data.subarray(p,p+4));
  need(data.length>=12&&[0x00010000,0x4f54544f,0x74727565].includes(u32(0)),'E_RESOURCE','Font is not supported SFNT',path);
  const count=u16(4);need(count>0&&count<=256&&12+16*count<=data.length,'E_RESOURCE','Invalid SFNT table directory',path);
  for(let i=0;i<count;i++){const at=12+i*16,name=tag(at),offset=u32(at+8),length=u32(at+12);need(!tables.has(name)&&offset>=12+16*count&&offset+length<=data.length,'E_RESOURCE','Invalid or duplicate SFNT table',path);tables.set(name,{offset,length});}
  const nonempty=[...tables.values()].filter(x=>x.length).sort((a,b)=>a.offset-b.offset);for(let i=1;i<nonempty.length;i++)need(nonempty[i].offset>=nonempty[i-1].offset+nonempty[i-1].length,'E_RESOURCE','Overlapping font tables',path);
  const table=(name,size)=>{const t=tables.get(name);need(t&&t.length>=size,'E_RESOURCE',`Font lacks valid ${name} table`,path);return t;};
  const head=table('head',54),maxp=table('maxp',6),hhea=table('hhea',36),glyphs=u16(maxp.offset+4),metrics=u16(hhea.offset+34);
  need(u32(head.offset+12)===0x5f0f3cf5&&u16(head.offset+18)>=16&&u16(head.offset+18)<=16384&&glyphs>0&&metrics>0&&metrics<=glyphs,'E_RESOURCE','Invalid font metrics',path);table('hmtx',4*metrics+2*(glyphs-metrics));
  const cmap=table('cmap',4),subtables=u16(cmap.offset+2);need(subtables>0&&4+subtables*8<=cmap.length,'E_RESOURCE','Invalid character map directory',path);
  let recognized=false;
  for(let i=0;i<subtables;i++){const relative=u32(cmap.offset+4+i*8+4),start=cmap.offset+relative;need(relative+2<=cmap.length,'E_RESOURCE','Character map outside table',path);const format=u16(start);let length;
    if([0,2,4,6].includes(format)){need(relative+4<=cmap.length,'E_RESOURCE','Truncated cmap',path);length=u16(start+2);}
    else if([8,10,12,13].includes(format)){need(relative+8<=cmap.length,'E_RESOURCE','Truncated cmap',path);length=u32(start+4);}
    else if(format===14){need(relative+6<=cmap.length,'E_RESOURCE','Truncated cmap',path);length=u32(start+2);}
    else continue;
    need(length>=4&&relative+length<=cmap.length,'E_RESOURCE','Character map range exceeds table',path);
    if(format===4){const segments=u16(start+6)/2;need(Number.isInteger(segments)&&segments>0&&16+segments*8<=length,'E_RESOURCE','Invalid cmap segments',path);recognized=true;}
    if(format===12||format===13){need(length>=16&&16+u32(start+12)*12<=length,'E_RESOURCE','Invalid cmap groups',path);recognized=true;}
    if(format===0||format===6)recognized=true;
  }
  need(recognized,'E_RESOURCE','No supported Unicode font character map',path);
  if(tables.has('glyf')){const glyf=table('glyf',0),long=u16(head.offset+50);need(long===0||long===1,'E_RESOURCE','Invalid glyph index format',path);const loca=table('loca',(glyphs+1)*(long?4:2));let previous=0;for(let i=0;i<=glyphs;i++){const offset=long?u32(loca.offset+i*4):u16(loca.offset+i*2)*2;need(offset>=previous&&offset<=glyf.length&&(offset===previous||offset-previous>=10),'E_RESOURCE','Invalid glyph data bounds',path);previous=offset;}}
  else need(tables.has('CFF ')||tables.has('CFF2'),'E_RESOURCE','Missing font outlines',path);
  const axes=Object.create(null),fvar=tables.get('fvar');
  if(fvar){need(fvar.length>=16,'E_RESOURCE','Truncated variation table',path);const start=u16(fvar.offset+4),axisCount=u16(fvar.offset+8),axisSize=u16(fvar.offset+10);need(axisSize>=20&&axisCount<=64&&start+axisCount*axisSize<=fvar.length,'E_RESOURCE','Invalid variation table',path);for(let i=0;i<axisCount;i++){const at=fvar.offset+start+i*axisSize,name=tag(at),min=view.getInt32(at+4)/65536,normal=view.getInt32(at+8)/65536,max=view.getInt32(at+12)/65536;need(!Object.hasOwn(axes,name)&&min<=normal&&normal<=max,'E_RESOURCE','Invalid font axis',path);axes[name]={min,max,default:normal};}}
  return {axes,glyphs,unitsPerEm:u16(head.offset+18)};
}

export function imageSize(data,mime,path='') {
  const v=new DataView(data.buffer,data.byteOffset,data.byteLength);let width=0,height=0;
  const prefix=arr=>data.length>=arr.length&&arr.every((x,i)=>data[i]===x),text=(p,n)=>String.fromCharCode(...data.subarray(p,p+n));
  if(mime==='image/png'){
    need(prefix([137,80,78,71,13,10,26,10])&&data.length>=33&&text(12,4)==='IHDR','E_RESOURCE','PNG header mismatch',path);width=v.getUint32(16);height=v.getUint32(20);let at=8,ended=false,idat=false;
    while(at<data.length){need(at+12<=data.length,'E_RESOURCE','Truncated PNG chunk',path);const size=v.getUint32(at),tag=text(at+4,4);need(at+12+size<=data.length,'E_RESOURCE','Invalid PNG chunk size',path);need(crc32(data.subarray(at+4,at+8+size))===v.getUint32(at+8+size),'E_RESOURCE','PNG chunk CRC mismatch',path);if(tag==='IDAT')idat=true;at+=12+size;if(tag==='IEND'){need(size===0&&at===data.length,'E_RESOURCE','PNG trailing bytes',path);ended=true;break;}}
    need(ended&&idat,'E_RESOURCE','PNG lacks image payload',path);
  }else if(mime==='image/jpeg'){
    need(prefix([255,216])&&data.length>=4&&data.at(-2)===255&&data.at(-1)===217,'E_RESOURCE','JPEG header or ending mismatch',path);let at=2;
    while(at+4<=data.length){need(data[at++]===255,'E_RESOURCE','Invalid JPEG marker',path);while(data[at]===255)at++;const marker=data[at++];if(marker===0xda||marker===0xd9)break;if(marker===1||marker>=0xd0&&marker<=0xd7)continue;need(at+2<=data.length,'E_RESOURCE','Truncated JPEG segment',path);const length=v.getUint16(at);need(length>=2&&at+length<=data.length,'E_RESOURCE','Invalid JPEG segment length',path);if([0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf].includes(marker)){need(length>=8,'E_RESOURCE','Invalid JPEG frame',path);height=v.getUint16(at+3);width=v.getUint16(at+5);}at+=length;}
  }else if(mime==='image/webp'){
    need(data.length>=20&&text(0,4)==='RIFF'&&text(8,4)==='WEBP'&&v.getUint32(4,true)+8===data.length,'E_RESOURCE','WebP RIFF mismatch',path);let payload=false;
    for(let at=12;at<data.length;){need(at+8<=data.length,'E_RESOURCE','Truncated WebP chunk',path);const tag=text(at,4),size=v.getUint32(at+4,true),start=at+8;need(start+size<=data.length,'E_RESOURCE','WebP chunk exceeds bounds',path);
      if(tag==='VP8X'){need(size===10,'E_RESOURCE','Invalid WebP extended header',path);width=1+data[start+4]+(data[start+5]<<8)+(data[start+6]<<16);height=1+data[start+7]+(data[start+8]<<8)+(data[start+9]<<16);need(!(data[start]&2),'E_RESOURCE','Animated WebP is unsupported',path);}
      if(tag==='VP8 '){need(size>=10&&data[start+3]===0x9d&&data[start+4]===1&&data[start+5]===0x2a,'E_RESOURCE','Invalid WebP frame',path);width ||= v.getUint16(start+6,true)&0x3fff;height ||= v.getUint16(start+8,true)&0x3fff;payload=true;}
      if(tag==='VP8L'){need(size>=5&&data[start]===0x2f,'E_RESOURCE','Invalid lossless WebP frame',path);const bits=v.getUint32(start+1,true);width ||= (bits&0x3fff)+1;height ||= ((bits>>>14)&0x3fff)+1;payload=true;}
      at=start+size+(size&1);need(at<=data.length,'E_RESOURCE','WebP padding missing',path);
    }need(payload,'E_RESOURCE','WebP lacks a frame',path);
  }else need(false,'E_RESOURCE','Unsupported image MIME',path);
  need(width>0&&height>0,'E_RESOURCE','Image dimensions missing',path);return {width,height};
}

export function validateResourceBytes(resource,data,{largeOem=false}={}) {
  const {kind,mediaType,path}=resource;
  if(kind==='font'){need(['font/ttf','font/otf'].includes(mediaType),'E_RESOURCE','Font MIME mismatch',path);const cff=data.length>=4&&String.fromCharCode(...data.subarray(0,4))==='OTTO';need(mediaType===(cff?'font/otf':'font/ttf'),'E_RESOURCE','Font MIME disagrees with its SFNT container',path);return inspectFont(data,path);}
  if(kind==='image'){
    need(['image/png','image/jpeg','image/webp','image/svg+xml'].includes(mediaType),'E_RESOURCE','Image MIME mismatch',path);
    const size=mediaType==='image/svg+xml'?validateSvg(decode(data,path)):imageSize(data,mediaType,path);
    need(size.width<=8192&&size.height<=8192&&size.width*size.height<=(largeOem?32000000:16000000),'E_LIMIT','Image dimensions exceed budget',path);return size;
  }
  need((['catalog','data'].includes(kind)&&mediaType==='application/json')||(kind==='license'&&mediaType==='text/plain'),'E_RESOURCE','Resource kind and MIME disagree',path);
  return mediaType==='application/json'?parseJson(data,path):decode(data,path);
}
