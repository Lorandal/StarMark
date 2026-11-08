import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {buildPresetConfig,PRESET_META} from '../src/presets/definitions.js';
import {configureRenderRuntime,renderTemplate,createSamplePhoto} from '../src/render/index.js';
const workbench=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),root=path.resolve(workbench,'..'),output=path.join(workbench,'public','presets');
const runtime=process.env.STARUW_CANVAS_MODULE||'@napi-rs/canvas';
const require=createRequire(import.meta.url),{createCanvas,loadImage,GlobalFonts}=require(runtime);
configureRenderRuntime({createCanvas,loadImage:bytes=>loadImage(Buffer.from(bytes)),registerFont:(bytes,family)=>GlobalFonts.register(Buffer.from(bytes),family)});
const hash=bytes=>createHash('sha256').update(bytes).digest('hex'),encode=value=>Buffer.from(JSON.stringify(value,null,2)+'\n'),resources={},files={},mapping={};
async function add(id,relative,bytes,kind,mediaType){const digest=hash(bytes),ext=path.extname(relative),publicPath=`shared/${digest}${ext}`;resources[id]={path:relative,kind,mediaType,sha256:digest};files[relative]=new Uint8Array(bytes);mapping[relative]=publicPath;await fs.mkdir(path.dirname(path.join(output,publicPath)),{recursive:true});await fs.writeFile(path.join(output,publicPath),bytes);}
for(const[name,file]of [['mi-sans','MiSansVF.ttf'],['oxanium','Oxanium.ttf'],['lg1056','LG1056.otf']])await add(`font-${name}`,`fonts/${file}`,await fs.readFile(path.join(root,'assets/fonts',file)),'font',file.endsWith('.otf')?'font/otf':'font/ttf');
for(const name of ['MarkC','MarkS'])await add(name==='MarkC'?'mark-c':'mark-s',`assets/${name}.svg`,await fs.readFile(path.join(root,'assets/marks/leica',name+'.svg')),'image','image/svg+xml');
const names=['Xiaomi','REDMI',...'0123456789STX','Civi','Fold','Max','MIX Flip','MIX Fold','Pad','Pro','Ultra','Lite','Ti','by Leica'],parts={};
function recolor(svg,color){const classes={};for(const match of svg.matchAll(/\.([\w-]+)\s*\{[^}]*fill\s*:\s*([^;}]+)/g))classes[match[1]]=match[2].trim();return svg.replace(/<\?xml[^>]*\?>/g,'').replace(/<style\b[^>]*>[\s\S]*?<\/style>/g,'').replace(/\sclass="([^"]+)"/g,(_,c)=>` fill="${color}"`).replace(/\sfill="(?!none)[^"]*"/g,` fill="${color}"`).replace(/<svg\b/,'<svg fill="'+color+'"');}
for(const name of names){const folder=['Xiaomi','REDMI'].includes(name)?'Brand':/^[0-9STX]$/.test(name)?'0-9, S, T':'Model',source=await fs.readFile(path.join(root,'assets/marks/models',folder,name+'.svg'),'utf8'),view=/viewBox="([^"]+)"/.exec(source)[1].split(/\s+/).map(Number),key=name.replaceAll(' ','-').toLowerCase();parts[name]={width:view[2],height:view[3],key};for(const color of ['black','white'])await add(`model-${key}-${color}`,`assets/models/${key}-${color}.svg`,Buffer.from(recolor(source,color==='white'?'#ffffff':'#141414')),'image','image/svg+xml');}
const optical={'0':.93,'1':.07,'2':2.60,'3':2.19,'4':10.68,'5':2.88,'6':2.77,'7':7.15,'8':3.27,'9':2.33,S:3.63,T:27.44,X:0},entries=[];
function compose(model,tokens){let x=0,top=0,bottom=80,previous;const components=[];for(const token of tokens){const p=parts[token],digit=/^[0-9STX]$/.test(token),kind=['Xiaomi','REDMI'].includes(token)?'brand':digit?'digit':'word';if(previous)x+=previous.kind==='brand'?45.6:previous.kind==='digit'&&kind==='digit'?(previous.key==='1'||token==='1'?10.4:Math.max(2,10.4-optical[previous.key])):previous.kind==='word'&&kind==='word'?30.4:30.6;const factor=p.height>80.01?1:80/p.height,w=p.width*factor,h=p.height*factor,y=['MIX Flip','by Leica'].includes(token)?0:80-h;top=Math.min(top,y);bottom=Math.max(bottom,y+h);components.push({resourceId:`model-${p.key}-COLOR`,x,y,width:w,height:h});x+=w;previous={key:token,kind};}for(const c of components)c.y-=top;entries.push({model,size:[x,bottom-top],baseline:80-top,components});}
for(const generation of ['12','13','14','15','16','17'])for(const suffix of ['', ' Pro',' Ultra',...(generation==='17'?[' Pro Max',' Ultra by Leica']:[])])compose(`Xiaomi ${generation}${suffix}`,['Xiaomi',...generation.split(''),...suffix.trim().split(/\s+/).filter(Boolean).reduce((a,t)=>{if(t==='by')a.push('by Leica');else if(t!=='Leica')a.push(t);return a;},[])]);
for(const [model,tokens]of [['Xiaomi MIX Flip',['Xiaomi','MIX Flip']],['Xiaomi MIX Fold 4',['Xiaomi','MIX Fold','4']],['Xiaomi Civi 4 Pro',['Xiaomi','Civi','4','Pro']],['Xiaomi Pad 7 Pro',['Xiaomi','Pad','7','Pro']],['Xiaomi 14T Pro',['Xiaomi','1','4','T','Pro']]])compose(model,tokens);
// REDMI suffixes preserve the audited MiSans 380/80U cap-height geometry.
GlobalFonts.register(Buffer.from(files['fonts/MiSansVF.ttf']),'CatalogMiSans');
for(const tail of ['K70','K70 Pro','K80','K80 Pro','K90','K90 Pro','Note 13 Pro','Note 14 Pro','Turbo 4','Turbo 4 Pro']){
  const probe=createCanvas(2,2).getContext('2d');probe.font='400 '+(80/.740)+'px CatalogMiSans';probe.fontVariationSettings='"wght" 380';const w=probe.measureText(tail).width,id=tail.toLowerCase().replaceAll(' ','-');
  for(const color of ['black','white']){const c=createCanvas(Math.ceil(w*4),320),ctx=c.getContext('2d');ctx.scale(4,4);ctx.font=probe.font;ctx.fontVariationSettings='"wght" 380';ctx.textBaseline='alphabetic';ctx.fillStyle=color==='white'?'#ffffff':'#141414';ctx.fillText(tail,0,80);await add(`redmi-${id}-${color}`,`assets/models/redmi-${id}-${color}.png`,c.toBuffer('image/png'),'image','image/png');}
  const brand=parts.REDMI;entries.push({model:`REDMI ${tail}`,size:[brand.width+45.6+w,80],baseline:80,components:[{resourceId:'model-redmi-COLOR',x:0,y:0,width:brand.width,height:80},{resourceId:`redmi-${id}-COLOR`,x:brand.width+45.6,y:0,width:w,height:80}]});
}
// The exact Leica edition is one complete, immutable wordmark image. It cannot
// be confused with the ordinary Ultra entry or rebuilt by a receiving device.
const leicaEdition=entries.find(e=>e.model==='Xiaomi 17 Ultra by Leica');
for(const color of ['black','white']){
  const artwork=leicaEdition.components.map(component=>{
    const id=component.resourceId.replace('COLOR',color),source=new TextDecoder().decode(files[resources[id].path]),view=/viewBox="([^"]+)"/.exec(source)[1].split(/\s+/).map(Number),body=source.replace(/^[\s\S]*?<svg\b[^>]*>/,'').replace(/<\/svg>\s*$/,'');
    return `<g transform="translate(${component.x} ${component.y}) scale(${component.width/view[2]} ${component.height/view[3]})">${body}</g>`;
  }).join('');
  const svg=`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${leicaEdition.size[0]} ${leicaEdition.size[1]}" fill="${color==='white'?'#ffffff':'#141414'}">${artwork}</svg>`;
  await add(`model-leica-edition-${color}`,`assets/models/xiaomi-17-ultra-by-leica-${color}.svg`,Buffer.from(svg),'image','image/svg+xml');
}
leicaEdition.components=[{resourceId:'model-leica-edition-COLOR',x:0,y:0,width:leicaEdition.size[0],height:leicaEdition.size[1]}];
for(const color of ['black','white']){
  const catalog={format:'com.realize.starmark.model-catalog',version:1,entries:entries.map(e=>({...e,components:e.components.map(c=>({...c,resourceId:c.resourceId.replace('COLOR',color)}))}))};
  await add(`catalog-${color}`,`catalogs/models-${color}.json`,encode(catalog),'catalog','application/json');
}
const provenance='StarMark template font files are bundled unchanged from the current App assets.\nOxanium, MiSans and LG1056 retain their original embedded copyright, license and name tables.\nModel component geometry follows the App ModelLockupAssets 80-unit baseline and optical spacing.\nMarkC.svg and MarkS.svg are the user-supplied StarMark marks.\n';
await add('asset-notices','licenses/ASSET-NOTICES.txt',Buffer.from(provenance),'license','text/plain');
const samplePhoto=createSamplePhoto(),sampleData={'photo.make':'Xiaomi','photo.model':'Xiaomi 15','exif.focalLength35mm':23,'exif.aperture':1.7,'exif.exposureSeconds':1/60,'exif.iso':400};
for(const preset of PRESET_META){
  const dir=path.join(output,preset.id);await fs.mkdir(dir,{recursive:true});const localResources=structuredClone(resources),localFiles={...files},localMap={...mapping},canvas=createCanvas(1,1);localResources.preview={path:'preview.png',kind:'image',mediaType:'image/png',sha256:'0'.repeat(64)};
  const config=buildPresetConfig(preset.id,localResources),pkg={config,files:localFiles,manifest:null,readOnly:false,warnings:[],mode:'full'};
  await renderTemplate(canvas,pkg,{photo:samplePhoto,metadata:sampleData,maxEdge:640});const preview=canvas.toBuffer('image/png');localResources.preview.sha256=hash(preview);localMap['preview.png']=`${preset.id}/preview.png`;await fs.writeFile(path.join(dir,'preview.png'),preview);await fs.writeFile(path.join(dir,'config.json'),encode(config));await fs.writeFile(path.join(dir,'files.json'),encode(localMap));
  process.stdout.write(`${preset.id}: ${canvas.width}x${canvas.height}, ${Object.keys(localMap).length} packaged files\n`);
}
const sharedRoot=path.resolve(output,'shared'),retained=new Set(Object.values(mapping).map(p=>path.basename(p)));
for(const name of await fs.readdir(sharedRoot))if(/^[0-9a-f]{64}\.[a-z0-9]+$/.test(name)&&!retained.has(name)){
  const target=path.resolve(sharedRoot,name);if(path.dirname(target)!==sharedRoot)throw new Error('Asset cleanup escaped its generated directory');await fs.unlink(target);
}
