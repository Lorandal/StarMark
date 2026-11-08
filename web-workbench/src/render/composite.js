import {makeCanvas} from './runtime.js';
export const toLinear=value=>value<=.04045?value/12.92:((value+.055)/1.055)**2.4;
export const toSrgb=value=>value<=.0031308?value*12.92:1.055*value**(1/2.4)-.055;
const lut=Float32Array.from({length:256},(_,i)=>toLinear(i/255));
export function floatLayer(x,y,width,height){return{x,y,width,height,data:new Float32Array(width*height*4)};}
export function layerFromCanvas(canvas,x=0,y=0){
  const result=floatLayer(x,y,canvas.width,canvas.height),pixels=canvas.getContext('2d',{willReadFrequently:true}).getImageData(0,0,canvas.width,canvas.height).data;
  for(let i=0;i<pixels.length;i+=4){const a=pixels[i+3]/255;result.data[i]=lut[pixels[i]]*a;result.data[i+1]=lut[pixels[i+1]]*a;result.data[i+2]=lut[pixels[i+2]]*a;result.data[i+3]=a;}return result;
}
export function composite(dest,source,opacity=1){
  const l=Math.max(dest.x,source.x),t=Math.max(dest.y,source.y),r=Math.min(dest.x+dest.width,source.x+source.width),b=Math.min(dest.y+dest.height,source.y+source.height),s=source.data,d=dest.data;
  for(let y=t;y<b;y++)for(let x=l;x<r;x++){const si=((y-source.y)*source.width+x-source.x)*4,di=((y-dest.y)*dest.width+x-dest.x)*4,a=s[si+3]*opacity,keep=1-a;if(!a)continue;d[di]=s[si]*opacity+d[di]*keep;d[di+1]=s[si+1]*opacity+d[di+1]*keep;d[di+2]=s[si+2]*opacity+d[di+2]*keep;d[di+3]=a+d[di+3]*keep;}
}
export function fillLinear(layer,color,opacity=1){
  const hex=color.slice(1),c=[0,2,4,6].map((p,i)=>parseInt(hex.slice(p,p+2)||'ff',16)/255),a=c[3]*opacity,r=toLinear(c[0])*a,g=toLinear(c[1])*a,b=toLinear(c[2])*a,keep=1-a;
  for(let i=0;i<layer.data.length;i+=4){layer.data[i]=r+layer.data[i]*keep;layer.data[i+1]=g+layer.data[i+1]*keep;layer.data[i+2]=b+layer.data[i+2]*keep;layer.data[i+3]=a+layer.data[i+3]*keep;}
}
export function writeLayer(canvas,layer){
  const ctx=canvas.getContext('2d'),image=ctx.createImageData(layer.width,layer.height),d=image.data,s=layer.data;
  for(let i=0;i<s.length;i+=4){const a=s[i+3];if(a>0){d[i]=Math.round(Math.min(1,toSrgb(s[i]/a))*255);d[i+1]=Math.round(Math.min(1,toSrgb(s[i+1]/a))*255);d[i+2]=Math.round(Math.min(1,toSrgb(s[i+2]/a))*255);d[i+3]=Math.round(a*255);}}ctx.putImageData(image,layer.x,layer.y);
}
export function canvasLayer(box,scale,paint){
  const l=Math.floor(box.x*scale),t=Math.floor(box.y*scale),r=Math.ceil((box.x+box.width)*scale),b=Math.ceil((box.y+box.height)*scale);
  if(r<=l||b<=t)return null;
  const canvas=makeCanvas(r-l,b-t),ctx=canvas.getContext('2d',{willReadFrequently:true});ctx.setTransform(scale,0,0,scale,-l,-t);paint(ctx);
  return layerFromCanvas(canvas,l,t);
}
/** Centre-cover resampling in premultiplied linear sRGB for material recipes. */
export function sampleCoverLinear(image,width,height,zoom=1){
  const iw=image.naturalWidth||image.width,ih=image.naturalHeight||image.height;
  if(iw*ih>24_000_000)throw new Error('E_LIMIT: 材质源图超出网页预览内存预算');
  const canvas=makeCanvas(iw,ih);canvas.getContext('2d').drawImage(image,0,0);const source=layerFromCanvas(canvas).data,out=floatLayer(0,0,width,height),factor=Math.max(width/iw,height/ih)*zoom;
  for(let y=0;y<height;y++)for(let x=0;x<width;x++){
    const sx=Math.min(iw-1,Math.max(0,(x+.5-width/2)/factor+iw/2-.5)),sy=Math.min(ih-1,Math.max(0,(y+.5-height/2)/factor+ih/2-.5)),x0=Math.floor(sx),y0=Math.floor(sy),x1=Math.min(iw-1,x0+1),y1=Math.min(ih-1,y0+1),fx=sx-x0,fy=sy-y0,a=(y0*iw+x0)*4,b=(y0*iw+x1)*4,c=(y1*iw+x0)*4,d=(y1*iw+x1)*4,at=(y*width+x)*4;
    for(let channel=0;channel<4;channel++)out.data[at+channel]=(source[a+channel]*(1-fx)+source[b+channel]*fx)*(1-fy)+(source[c+channel]*(1-fx)+source[d+channel]*fx)*fy;
  }
  return out;
}
/** Exact separable Gaussian, truncated at 3 sigma, normalized, clamped edges. */
export function gaussianBlur(layer,sigma){
  if(sigma<.01)return layer;const radius=Math.ceil(3*sigma),kernel=Float32Array.from({length:radius*2+1},(_,i)=>Math.exp(-.5*((i-radius)/sigma)**2)),sum=kernel.reduce((a,b)=>a+b,0);for(let i=0;i<kernel.length;i++)kernel[i]/=sum;
  const temp=new Float32Array(layer.data.length),out=new Float32Array(layer.data.length),{width:w,height:h,data}=layer;
  function pass(input,output,vertical){for(let y=0;y<h;y++)for(let x=0;x<w;x++){
    const at=(y*w+x)*4,k0=kernel[radius];let r=input[at]*k0,g=input[at+1]*k0,b=input[at+2]*k0,a=input[at+3]*k0;
    // Gaussian symmetry halves multiplications and shares index/clamp work
    // across all four premultiplied channels, without changing the kernel.
    for(let k=1;k<=radius;k++){const left=vertical?(Math.max(0,y-k)*w+x)*4:(y*w+Math.max(0,x-k))*4,right=vertical?(Math.min(h-1,y+k)*w+x)*4:(y*w+Math.min(w-1,x+k))*4,weight=kernel[radius+k];r+=(input[left]+input[right])*weight;g+=(input[left+1]+input[right+1])*weight;b+=(input[left+2]+input[right+2])*weight;a+=(input[left+3]+input[right+3])*weight;}
    output[at]=r;output[at+1]=g;output[at+2]=b;output[at+3]=a;
  }}
  pass(data,temp,false);pass(temp,out,true);
  return {...layer,data:out};
}
