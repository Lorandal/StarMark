import {createGeometry,resolveBounds,identity,multiply,nodeTransform,transformBox,roundEdge} from './geometry.js';
import {applyControls} from './controls.js';
import {resolveContent,matchModel} from './metadata.js';
import {loadResources} from './resources.js';
import {prepareText} from './text.js';
import {makeCanvas} from './runtime.js';
import {floatLayer,layerFromCanvas,canvasLayer,composite,fillLinear,writeLayer,gaussianBlur,sampleCoverLinear} from './composite.js';

function union(a,b){if(!a)return b;if(!b)return a;const x=Math.min(a.x,b.x),y=Math.min(a.y,b.y);return{x,y,width:Math.max(a.x+a.width,b.x+b.width)-x,height:Math.max(a.y+a.height,b.y+b.height)-y};}
function intersect(a,b){const x=Math.max(a.x,b.x),y=Math.max(a.y,b.y);return{x,y,width:Math.max(0,Math.min(a.x+a.width,b.x+b.width)-x),height:Math.max(0,Math.min(a.y+a.height,b.y+b.height)-y)};}
export function drawFit(ctx,image,rect,fit='contain',zoom=1){
  const width=image.naturalWidth||image.width,height=image.naturalHeight||image.height,factor=(fit==='cover'?Math.max(rect.width/width,rect.height/height):Math.min(rect.width/width,rect.height/height))*zoom;
  ctx.save();ctx.beginPath();ctx.rect(rect.x,rect.y,rect.width,rect.height);ctx.clip();ctx.drawImage(image,rect.x+(rect.width-width*factor)/2,rect.y+(rect.height-height*factor)/2,width*factor,height*factor);ctx.restore();
}
let sample;
const materialCache=new WeakMap();
export function createSamplePhoto(){
  if(sample)return sample;sample=makeCanvas(1440,1080);const c=sample.getContext('2d'),sky=c.createLinearGradient(0,0,0,1080);sky.addColorStop(0,'#c1cfd4');sky.addColorStop(.6,'#e5ded1');sky.addColorStop(1,'#486c77');c.fillStyle=sky;c.fillRect(0,0,1440,1080);
  for(const [color,base,peaks]of [['#819b9e',670,[[0,570],[190,410],[360,530],[590,270],[810,420],[1040,330],[1250,520],[1440,440]]],['#546f76',850,[[0,650],[230,610],[470,720],[720,500],[950,650],[1180,600],[1440,740]]],['#284b56',1080,[[0,830],[240,730],[500,880],[760,800],[1100,900],[1440,780]]]]){c.fillStyle=color;c.beginPath();c.moveTo(0,base);for(const p of peaks)c.lineTo(...p);c.lineTo(1440,1080);c.lineTo(0,1080);c.closePath();c.fill();}
  return sample;
}
function photoInfo(photo){const image=photo?.image||photo||createSamplePhoto();return{image,width:photo?.width||image.naturalWidth||image.width,height:photo?.height||image.naturalHeight||image.height};}

export async function renderScene(canvas,pkg,options={}){
  const photo=photoInfo(options.photo),{scene,metadata}=applyControls(pkg.config,options.metadata,options.photoValues),g=createGeometry(photo.width,photo.height,scene.canvas),resources=await loadResources(pkg,scene);
  const scale=options.scale??Math.min(g.nativeScale,(options.maxEdge||1600)/Math.max(g.all.width,g.all.height));
  const width=roundEdge(g.all.width*scale),height=roundEdge(g.all.height*scale);if(width*height>24_000_000||width>16384||height>16384||!(width>0&&height>0))throw new Error('E_LIMIT: 预览画布超过浏览器预算');
  const warnings=[{code:'W_PREVIEW_ONLY',severity:'warning',message:'网页画布使用 SDR sRGB 预览；照片 HDR、Live 与原色彩输出由 App 处理。'}],boxes=[];
  async function prepare(n,parent,matrix,clips=[]){
    const ref=g.reference(n.bounds.reference,parent),rect=resolveBounds(ref,n.bounds),local={x:0,y:0,width:rect.width,height:rect.height},m=multiply(matrix,nodeTransform(rect,n.bounds.pivot,n.rotation||0));
    const ownBox=transformBox(local,m),plan={node:n,matrix:m,clips,box:intersect(ownBox,g.all),opacity:n.opacity??1,children:[]};
    if(n.type==='group'){
      const replacement=n.replacement;
      if(replacement?.mode==='image'){const image=await resources.image(replacement.resourceId);plan.draw=c=>drawFit(c,image,local,replacement.fit);}
      else if(replacement?.mode==='text'){const style=n.replacementStyle,text=prepareText(replacement.text,replacement.fontId||style.fontId,style.textStyle,rect,resources);plan.draw=c=>text.draw(c);}
      else{
        const childClips=n.clipChildren?[...clips,{matrix:m,rect:local}]:clips;
        for(const child of n.children||[])if(child.visible!==false&&(child.opacity??1)>0)plan.children.push(await prepare(child,local,m,childClips));
        const childrenBox=plan.children.reduce((a,p)=>union(a,p.box),null);plan.box=intersect(n.clipChildren?intersect(childrenBox||ownBox,ownBox):childrenBox||ownBox,g.all);
      }
    }else if(n.type==='image'){
      const image=await resources.image(n.resourceId);plan.draw=c=>{let area=local;if(n.shape==='circle'){const side=Math.min(rect.width,rect.height);if(!n.replacementCircle&&rect.width!==rect.height)throw new Error('E_BINDING: 圆形图片必须使用正方形边界');area={x:(rect.width-side)/2,y:(rect.height-side)/2,width:side,height:side};c.beginPath();c.arc(rect.width/2,rect.height/2,side/2,0,Math.PI*2);c.clip();}drawFit(c,image,area,n.fit);};
    }else if(n.type==='text'){
      const text=resolveContent(n.content,metadata);if(text){const layout=prepareText(text,n.fontId,n.textStyle,rect,resources);plan.draw=c=>layout.draw(c);}
    }else if(n.type==='model'){
      const model=matchModel(resources.catalogs[n.catalogId],metadata);
      if(model){const fit=Math.min(rect.width/model.size[0],rect.height/model.size[1]),dx=(rect.width-model.size[0]*fit)/2,dy=(rect.height-model.size[1]*fit)/2;
        for(const part of model.components){const image=await resources.image(part.resourceId),r={x:dx+part.x*fit,y:dy+part.y*fit,width:part.width*fit,height:part.height*fit};plan.children.push({node:{},matrix:m,clips,box:intersect(transformBox(r,m),g.all),opacity:1,children:[],draw:c=>drawFit(c,image,r,'contain')});}
      }else if(metadata['photo.model']){const text=prepareText(metadata['photo.model'],n.fallbackFontId,n.textStyle,rect,resources);plan.draw=c=>text.draw(c);}
    }else throw new Error(`E_UNSUPPORTED: 未支持的节点 ${n.type}`);
    return plan;
  }
  const plans=[];
  for(const n of scene.nodes){const ref=g.reference(n.bounds.reference);if(ref){const r=resolveBounds({...ref,width:Math.max(ref.width,1e-9),height:Math.max(ref.height,1e-9)},n.bounds);boxes.push({id:n.id,...r,rotation:n.rotation||0,pivot:n.bounds.pivot,visible:n.visible!==false});}if(n.visible!==false&&(n.opacity??1)>0)plans.push(await prepare(n,null,identity()));}
  let workingPixels=width*height;
  function paint(plan){
    if(plan.box.width<=0||plan.box.height<=0)return null;
    if(plan.children.length){const l=Math.floor(plan.box.x*scale),t=Math.floor(plan.box.y*scale),r=Math.ceil((plan.box.x+plan.box.width)*scale),b=Math.ceil((plan.box.y+plan.box.height)*scale),pixels=(r-l)*(b-t);if(workingPixels+pixels>32_000_000)throw new Error('E_LIMIT: 嵌套组预览超出内存预算');workingPixels+=pixels;const layer=floatLayer(l,t,r-l,b-t);for(const p of plan.children){const child=paint(p);if(child)composite(layer,child,p.opacity);}workingPixels-=pixels;return layer;}
    if(!plan.draw)return null;
    return canvasLayer(plan.box,scale,ctx=>{const base=ctx.getTransform();for(const clip of plan.clips){ctx.setTransform(base);ctx.transform(...clip.matrix);ctx.beginPath();ctx.rect(clip.rect.x,clip.rect.y,clip.rect.width,clip.rect.height);ctx.clip();}ctx.setTransform(base);ctx.transform(...plan.matrix);plan.draw(ctx);});
  }
  const output=floatLayer(0,0,width,height);let bg=scene.canvas.background;
  if(bg.kind==='material'){
    let recipe=scene.materials[bg.materialId];if(recipe.kind==='photo-glass'){warnings.push({code:'W_MATERIAL_FALLBACK',severity:'warning',message:'当前网页使用模板声明的玻璃备用效果。',backend:'fallback'});recipe=recipe.fallback;}
    if(recipe.kind==='solid')bg=recipe;else if(recipe.kind==='photo-blur'){
      const key=JSON.stringify([width,height,scale,recipe.scale,recipe.blurSigmaU]);let cached=materialCache.get(photo.image);
      if(cached?.key!==key){const layer=sampleCoverLinear(photo.image,width,height,recipe.scale);cached={key,layer:gaussianBlur(layer,recipe.blurSigmaU*scale)};materialCache.set(photo.image,cached);}
      composite(output,cached.layer);fillLinear(output,recipe.tint,recipe.tintOpacity);
      if(recipe.highlightWidthU>0){const highlight=canvasLayer(g.all,scale,ctx=>{ctx.lineWidth=recipe.highlightWidthU;ctx.strokeStyle=recipe.highlightColor;const p=recipe.highlightWidthU/2;ctx.strokeRect(p,p,g.all.width-2*p,g.all.height-2*p);});composite(output,highlight);}bg=null;
    }else throw new Error(`E_CAPABILITY: 未支持的材质 ${recipe.kind}`);
  }
  if(bg?.kind==='solid')fillLinear(output,bg.color);else if(bg?.kind==='image'){const image=await resources.image(bg.resourceId);composite(output,canvasLayer(g.all,scale,c=>drawFit(c,image,g.all,bg.fit)));}
  // Round absolute photo edges; do not independently round an inset and width.
  const px=roundEdge(g.photo.x*scale),py=roundEdge(g.photo.y*scale),pr=roundEdge((g.photo.x+g.photo.width)*scale),pb=roundEdge((g.photo.y+g.photo.height)*scale),photocanvas=makeCanvas(pr-px,pb-py);photocanvas.getContext('2d').drawImage(photo.image,0,0,pr-px,pb-py);composite(output,layerFromCanvas(photocanvas,px,py));
  for(const plan of plans){const layer=paint(plan);if(layer)composite(output,layer,plan.opacity);}
  canvas.width=width;canvas.height=height;writeLayer(canvas,output);
  return {width:g.all.width,height:g.all.height,scale,boxes,warnings,photo:g.photo};
}
