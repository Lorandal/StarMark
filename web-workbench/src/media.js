import opentype from 'opentype.js';
import {validateSvg,sha256} from './staruw/index.js';
import {changePackage,uid} from './editor-state.js';
export const SAMPLE_METADATA={'photo.make':'Xiaomi','photo.model':'Xiaomi 15','exif.focalLength35mm':23,'exif.aperture':1.7,'exif.exposureSeconds':1/60,'exif.iso':400};
export function readImage(source){return new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>resolve(image);image.onerror=()=>reject(Error('图片无法读取，请选择有效的 PNG、JPEG 或 WebP'));image.src=source;});}
export async function imageFromBlob(blob){const url=URL.createObjectURL(blob);try{return await readImage(url);}finally{URL.revokeObjectURL(url);}}
export const samplePhoto=async()=>({image:await readImage('/assets/img/mountain-opt.jpg'),name:'示例照片',isSample:true,blob:null});
export async function canvasBlob(canvas,type='image/png'){return new Promise((resolve,reject)=>canvas.toBlob(blob=>blob?resolve(blob):reject(Error('浏览器无法创建图片，请降低输出尺寸后重试')),type));}
export function download(bytes,name,type='application/zip'){const blob=bytes instanceof Blob?bytes:new Blob([bytes],{type});const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=safeName(name);document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);}
export const safeName=name=>(name||'StarMark').replace(/[<>:"/\\|?*\x00-\x1f]/g,'_').slice(0,140);
async function attach(pkg,file,kind,mediaType,bytes,extension){const id=uid(kind);const path=`${kind==='font'?'fonts':'assets'}/${id}.${extension}`;const digest=await sha256(bytes);return {id,pkg:changePackage(pkg,(s,p)=>{s.resources[id]={path,kind,mediaType,sha256:digest};p.files[path]=bytes;})};}
export async function attachImage(pkg,file,{allowTypes}={}){
 const sourceType=file.type||(/\.svg$/i.test(file.name)?'image/svg+xml':/\.jpe?g$/i.test(file.name)?'image/jpeg':/\.webp$/i.test(file.name)?'image/webp':'image/png');if(allowTypes&&!allowTypes.includes(sourceType))throw Error('此模板不允许这种图片格式');
 if(file.size>16*1024*1024)throw Error('单张设计素材请小于 16 MB');
 if(/\.svg$/i.test(file.name)||file.type==='image/svg+xml'){const text=await file.text();validateSvg(text);const bytes=new TextEncoder().encode(text);const im=await imageFromBlob(new Blob([bytes],{type:'image/svg+xml'}));const result=await attach(pkg,file,'image','image/svg+xml',bytes,'svg');return {...result,aspect:im.naturalWidth/im.naturalHeight};}
 if(!/\.(jpe?g|png|webp)$/i.test(file.name))throw Error('素材支持 SVG、PNG、JPEG、WebP');
 const image=await imageFromBlob(file);if(image.naturalWidth*image.naturalHeight>16e6)throw Error('设计素材请控制在 1600 万像素以内');
 const canvas=document.createElement('canvas');canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;canvas.getContext('2d').drawImage(image,0,0);const type=!allowTypes||allowTypes.includes('image/png')?'image/png':allowTypes.includes('image/jpeg')?'image/jpeg':'image/webp';const blob=await canvasBlob(canvas,type);const bytes=new Uint8Array(await blob.arrayBuffer());const result=await attach(pkg,file,'image',blob.type,bytes,blob.type==='image/png'?'png':blob.type==='image/jpeg'?'jpg':'webp');return {...result,aspect:canvas.width/canvas.height};
}
export async function attachFont(pkg,file){
 if(!/\.(ttf|otf)$/i.test(file.name)||file.size>32*1024*1024)throw Error('请选择 32 MB 以内、允许随模板分发的 TTF 或 OTF 字体');
 const buffer=await file.arrayBuffer();let parsed;try{parsed=opentype.parse(buffer);}catch{throw Error('字体文件结构无效，请使用有效的 TTF / OTF');}
 const result=await attach(pkg,file,'font',/\.otf$/i.test(file.name)?'font/otf':'font/ttf',new Uint8Array(buffer),/\.otf$/i.test(file.name)?'otf':'ttf');
 const axes={};for(const axis of parsed.tables.fvar?.axes||[])axes[axis.tag]=axis.defaultValue;
 const fontId=uid('font');const next=changePackage(result.pkg,s=>{const fonts=s.scene?.fonts||s.xiaomi?.fonts;if(!fonts)throw Error('模板未声明字体表');fonts[fontId]={resourceId:result.id,axes,fallbackFontIds:[]};});
 return {pkg:next,fontId,name:parsed.names.fontFamily?.en||file.name};
}
export function syntheticPhoto(){const canvas=document.createElement('canvas');canvas.width=810;canvas.height=1080;const ctx=canvas.getContext('2d');const sky=ctx.createLinearGradient(0,0,810,1080);sky.addColorStop(0,'#cfdbd2');sky.addColorStop(.5,'#becbc2');sky.addColorStop(1,'#5d796e');ctx.fillStyle=sky;ctx.fillRect(0,0,810,1080);ctx.fillStyle='#738d7e';ctx.beginPath();ctx.moveTo(0,600);ctx.lineTo(290,270);ctx.lineTo(560,670);ctx.lineTo(810,440);ctx.lineTo(810,1080);ctx.lineTo(0,1080);ctx.fill();ctx.fillStyle='#375f50';ctx.beginPath();ctx.moveTo(0,790);ctx.lineTo(260,570);ctx.lineTo(520,790);ctx.lineTo(810,690);ctx.lineTo(810,1080);ctx.lineTo(0,1080);ctx.fill();return canvas;}
