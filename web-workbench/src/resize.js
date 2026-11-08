/** Drag a local bottom-right corner while holding the opposite world corner. */
export function resizeFromDelta({offset,size,pivot=[.5,.5],rotation=0,shape},dx,dy){
 const rad=rotation*Math.PI/180,c=Math.cos(rad),s=Math.sin(rad);
 const local=[dx*c+dy*s,-dx*s+dy*c];
 let next=[Math.max(1,size[0]+local[0]),Math.max(1,size[1]+local[1])];
 if(shape==='circle'){const side=Math.max(1,Math.max(next[0],next[1]));next=[side,side];}
 const delta=[next[0]-size[0],next[1]-size[1]],p=[pivot[0]*delta[0],pivot[1]*delta[1]],shift=[c*p[0]-s*p[1],s*p[0]+c*p[1]];
 return {size:next,offset:[offset[0]+shift[0],offset[1]+shift[1]],delta,originDelta:[shift[0]-p[0],shift[1]-p[1]]};
}
