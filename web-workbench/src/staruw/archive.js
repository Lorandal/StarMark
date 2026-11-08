import { inflateSync, zipSync } from 'fflate';
import { LIMITS, need, StaruwError, bytes, decode, checkPaths, equalBytes } from './util.js';

const crcTable=Uint32Array.from({length:256},(_,n)=>{for(let k=0;k<8;k++)n=n&1?0xedb88320^(n>>>1):n>>>1;return n>>>0;});
export function crc32(data) {let n=0xffffffff;for(const x of data)n=crcTable[(n^x)&255]^(n>>>8);return (n^0xffffffff)>>>0;}

// Count the actual Deflate output BEFORE allocation. A dishonest central length
// cannot turn inflateSync's growing buffer into an unbounded ZIP bomb.
export function inspectDeflate(data,expected,limit) {
  let bit=0,produced=0,work=0;
  const bits=n=>{need(bit+n<=data.length*8,'E_ZIP','Truncated Deflate stream');let value=0;for(let i=0;i<n;i++,bit++)value|=((data[bit>>>3]>>>(bit&7))&1)<<i;return value;};
  function tree(lengths) {
    const counts=new Uint16Array(16);for(const n of lengths){need(n>=0&&n<=15,'E_ZIP','Invalid Huffman length');if(n)counts[n]++;}
    let remaining=1;for(let i=1;i<=15;i++){remaining=remaining*2-counts[i];need(remaining>=0,'E_ZIP','Oversubscribed Huffman tree');}
    const next=new Uint16Array(16),map=new Map();let code=0,max=0;
    for(let i=1;i<=15;i++){code=(code+counts[i-1])*2;next[i]=code;}
    lengths.forEach((length,symbol)=>{if(length){let c=next[length]++,reversed=0;for(let j=0;j<length;j++){reversed=(reversed<<1)|(c&1);c>>>=1;}map.set((1<<length)|reversed,symbol);max=Math.max(max,length);}});
    return {map,max};
  }
  function symbol(t) { let code=0;for(let length=1;length<=t.max;length++){code|=bits(1)<<(length-1);const v=t.map.get((1<<length)|code);if(v!==undefined)return v;}throw new StaruwError('E_ZIP','Invalid Huffman symbol'); }
  const lengthBase=[3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258];
  const lengthExtra=[0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0];
  const distanceBase=[1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577];
  const add=n=>{produced+=n;need(produced<=limit,'E_LIMIT','Actual Deflate output exceeds budget');need(produced<=expected,'E_ZIP','Actual Deflate output exceeds declared size');};
  let final=0;
  while(!final) {
    need(++work<=Math.max(1000000,data.length*16),'E_LIMIT','Deflate work budget exceeded');
    final=bits(1);const type=bits(2);
    if(type===0){bit=(bit+7)&~7;const length=bits(16),inverse=bits(16);need((length^inverse)===65535,'E_ZIP','Invalid stored Deflate block');add(length);need(bit+length*8<=data.length*8,'E_ZIP','Truncated stored block');bit+=length*8;continue;}
    need(type!==3,'E_ZIP','Reserved Deflate block');let literals,distances;
    if(type===1){literals=tree(Array.from({length:288},(_,i)=>i<144?8:i<256?9:i<280?7:8));distances=tree(Array(32).fill(5));}
    else {
      const nl=bits(5)+257,nd=bits(5)+1,nc=bits(4)+4;need(nl<=286,'E_ZIP','Invalid Deflate literal count');
      const codeOrder=[16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15],lengths=Array(19).fill(0);
      for(let i=0;i<nc;i++)lengths[codeOrder[i]]=bits(3);const codes=tree(lengths),all=[];
      while(all.length<nl+nd){const x=symbol(codes);if(x<16)all.push(x);else{need(x<=18 && (x!==16||all.length),'E_ZIP','Invalid Huffman repetition');const count=x===16?bits(2)+3:x===17?bits(3)+3:bits(7)+11,value=x===16?all.at(-1):0;need(all.length+count<=nl+nd,'E_ZIP','Huffman repetition overflow');for(let i=0;i<count;i++)all.push(value);}}
      need(all[256]>0,'E_ZIP','Deflate end symbol is absent');literals=tree(all.slice(0,nl));distances=tree(all.slice(nl));
    }
    while(true){const x=symbol(literals);if(x===256)break;if(x<256)add(1);else{need(x<=285,'E_ZIP','Invalid Deflate length');const n=lengthBase[x-257]+bits(lengthExtra[x-257]),d=symbol(distances);need(d<30,'E_ZIP','Invalid Deflate distance');const distance=distanceBase[d]+bits(d<4?0:(d>>>1)-1);need(distance<=produced,'E_ZIP','Deflate distance precedes data');add(n);}}
  }
  need(produced===expected&&Math.ceil(bit/8)===data.length,'E_ZIP','Deflate length or trailing data mismatch');
}

function extras(data) { const view=new DataView(data.buffer,data.byteOffset,data.byteLength);for(let at=0;at<data.length;){need(at+4<=data.length,'E_ZIP','Truncated ZIP extra field');const tag=view.getUint16(at,true),size=view.getUint16(at+2,true);need(tag!==1,'E_ZIP','ZIP64 is unsupported');at+=4+size;need(at<=data.length,'E_ZIP','Invalid ZIP extra field');} }
export function inspectZip(input) {
  const data=bytes(input);need(data.length>=22&&data.length<=LIMITS.bundleZip,'E_LIMIT','Archive size exceeds budget or is empty');const view=new DataView(data.buffer,data.byteOffset,data.byteLength);
  const u16=p=>{need(p>=0&&p+2<=data.length,'E_ZIP','Truncated ZIP');return view.getUint16(p,true);},u32=p=>{need(p>=0&&p+4<=data.length,'E_ZIP','Truncated ZIP');return view.getUint32(p,true);};
  let end=-1;for(let p=data.length-22;p>=Math.max(0,data.length-65557);p--)if(u32(p)===0x06054b50 && p+22+u16(p+20)===data.length){end=p;break;}
  need(end>=0,'E_ZIP','Missing ZIP end record');need(u16(end+4)===0&&u16(end+6)===0&&u16(end+8)===u16(end+10),'E_ZIP','Multi-volume ZIP is unsupported');
  const count=u16(end+10),size=u32(end+12),offset=u32(end+16);need(count>0&&count<=LIMITS.entries,'E_LIMIT','ZIP entry budget exceeded');need(count!==65535&&size!==0xffffffff&&offset!==0xffffffff,'E_ZIP','ZIP64 is unsupported');need(offset+size===end,'E_ZIP','Invalid ZIP central directory');
  const entries=[];let at=offset;
  for(let i=0;i<count;i++){
    need(u32(at)===0x02014b50&&at+46<=end,'E_ZIP','Invalid central entry');const flags=u16(at+8),method=u16(at+10),crc=u32(at+16),compressed=u32(at+20),expanded=u32(at+24),nameLength=u16(at+28),extraLength=u16(at+30),commentLength=u16(at+32),local=u32(at+42),attributes=u32(at+38),madeBy=u16(at+4);
    need((flags&~0x080e)===0 && [0,8].includes(method),'E_ZIP','Encrypted or unsupported ZIP method');need(method===8 || !(flags&6),'E_ZIP','Invalid stored ZIP flags');need(u16(at+34)===0&&compressed!==0xffffffff&&expanded!==0xffffffff&&local!==0xffffffff,'E_ZIP','ZIP64 or disk entry is unsupported');
    const endEntry=at+46+nameLength+extraLength+commentLength;need(endEntry<=end,'E_ZIP','Truncated central directory');const rawName=data.subarray(at+46,at+46+nameLength),path=decode(rawName),directory=path.endsWith('/');
    const unixType=(attributes>>>16)&0xf000;need(![0xa000,0x6000,0x2000,0x1000,0xc000].includes(unixType) && (unixType===0||unixType===(directory?0x4000:0x8000)),'E_ZIP','Links and non-regular ZIP entries are forbidden',path);
    need(!(attributes&0x400),'E_ZIP','Reparse ZIP entry is forbidden',path);extras(data.subarray(at+46+nameLength,at+46+nameLength+extraLength));
    need(local<offset&&u32(local)===0x04034b50&&u16(local+6)===flags&&u16(local+8)===method,'E_ZIP','Local ZIP header disagrees with central header',path);
    const localNameLength=u16(local+26),localExtraLength=u16(local+28),start=local+30+localNameLength+localExtraLength;
    need(localNameLength===nameLength&&equalBytes(rawName,data.subarray(local+30,local+30+localNameLength))&&start+compressed<=offset,'E_ZIP','Local ZIP path or range mismatch',path);extras(data.subarray(local+30+localNameLength,start));
    let finish=start+compressed;
    if(flags&8){need([0,crc].includes(u32(local+14))&&[0,compressed].includes(u32(local+18))&&[0,expanded].includes(u32(local+22)),'E_ZIP','Invalid local descriptor sizes',path);if(u32(finish)===0x08074b50)finish+=4;need(u32(finish)===crc&&u32(finish+4)===compressed&&u32(finish+8)===expanded,'E_ZIP','ZIP data descriptor mismatch',path);finish+=12;}
    else need(u32(local+14)===crc&&u32(local+18)===compressed&&u32(local+22)===expanded,'E_ZIP','Local ZIP size or CRC mismatch',path);
    need(finish<=offset&&(!directory||(expanded===0&&compressed===0||expanded===0&&method===8)),'E_ZIP','Invalid directory entry',path);
    entries.push({path,directory,method,crc,compressed,expanded,local,start,finish});at=endEntry;
  }
  need(at===end,'E_ZIP','Unexpected central directory bytes');checkPaths(entries);
  let cursor=0;for(const e of [...entries].sort((a,b)=>a.local-b.local)){need(e.local===cursor,'E_ZIP','Overlapping ZIP entries, prefix or unindexed content',e.path);cursor=e.finish;}need(cursor===offset,'E_ZIP','ZIP contains unindexed data');
  return {data,entries};
}
export function openZip(input,{bundle=false}={}) {
  const archive=Array.isArray(input?.entries)?input:inspectZip(input),{data,entries}=archive;
  need(data.length<=(bundle?LIMITS.bundleZip:LIMITS.zip),'E_LIMIT','Compressed archive exceeds budget');let total=0;const files=Object.create(null);
  for(const e of entries){const cap=bundle?(e.path.endsWith('.zip')?LIMITS.zip:LIMITS.bundleManifest):LIMITS.file;total+=e.expanded;need(e.expanded<=cap&&total<=(bundle?LIMITS.bundleExpanded:LIMITS.expanded),'E_LIMIT','Expanded archive exceeds budget',e.path);
    const compressed=data.subarray(e.start,e.start+e.compressed);let result;
    if(e.method===0){need(e.compressed===e.expanded,'E_ZIP','Stored ZIP size mismatch',e.path);result=compressed.slice();}
    else {inspectDeflate(compressed,e.expanded,cap);try{result=inflateSync(compressed,{out:new Uint8Array(e.expanded)});}catch{throw new StaruwError('E_ZIP','Deflate decompression failed',e.path);}}
    need(result.length===e.expanded&&crc32(result)===e.crc,'E_ZIP','ZIP length or CRC mismatch',e.path);if(!e.directory)files[e.path]=result;
  }
  return {files,expanded:total};
}
export function makeZip(files) { try { return zipSync(files,{level:6,mtime:new Date('2026-01-01T00:00:00Z')}); } catch(error){throw new StaruwError('E_ZIP','Could not encode ZIP');} }
