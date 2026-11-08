export const roundEdge = value => Math.floor(value + .5);
export function createGeometry(width, height, canvas) {
  if (!(width > 0 && height > 0)) throw new Error('E_LIMIT: 无效照片尺寸');
  const nativeScale = Math.min(width,height) / 1080, w = width/nativeScale, h = height/nativeScale;
  const {top:T=0,right:R=0,bottom:B=0,left:L=0} = canvas.insets;
  const all = {x:0,y:0,width:L+w+R,height:T+h+B}, photo = {x:L,y:T,width:w,height:h};
  const references = {canvas:all,photo,frameTop:{x:0,y:0,width:all.width,height:T},frameBottom:{x:0,y:T+h,width:all.width,height:B},frameLeft:{x:0,y:T,width:L,height:h},frameRight:{x:L+w,y:T,width:R,height:h}};
  return {all,photo,nativeScale,reference:(name,parent)=>name==='parent'?parent:references[name]};
}
export function resolveBounds(reference,bounds) {
  if (!reference || reference.width <= 0 || reference.height <= 0) throw new Error('E_BINDING: 可见节点引用零面积或无效区域');
  const {anchor:a,pivot:p,offset:o,size:s}=bounds;
  return {x:reference.x+a[0]*reference.width+o[0]-p[0]*s[0],y:reference.y+a[1]*reference.height+o[1]-p[1]*s[1],width:s[0],height:s[1]};
}
export const identity = () => [1,0,0,1,0,0];
export function multiply(a,b) {return [a[0]*b[0]+a[2]*b[1],a[1]*b[0]+a[3]*b[1],a[0]*b[2]+a[2]*b[3],a[1]*b[2]+a[3]*b[3],a[0]*b[4]+a[2]*b[5]+a[4],a[1]*b[4]+a[3]*b[5]+a[5]];}
export function nodeTransform(rect,pivot,rotation=0) {
  const a=rotation*Math.PI/180,c=Math.cos(a),s=Math.sin(a),px=pivot[0]*rect.width,py=pivot[1]*rect.height;
  return [c,s,-s,c,rect.x+px-c*px+s*py,rect.y+py-s*px-c*py];
}
export function transformBox(rect,m) {
  const points=[[rect.x,rect.y],[rect.x+rect.width,rect.y],[rect.x+rect.width,rect.y+rect.height],[rect.x,rect.y+rect.height]].map(([x,y])=>[m[0]*x+m[2]*y+m[4],m[1]*x+m[3]*y+m[5]]);
  const xs=points.map(p=>p[0]),ys=points.map(p=>p[1]); return {x:Math.min(...xs),y:Math.min(...ys),width:Math.max(...xs)-Math.min(...xs),height:Math.max(...ys)-Math.min(...ys)};
}
