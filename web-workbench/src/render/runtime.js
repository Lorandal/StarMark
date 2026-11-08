let overrides={};
/** Test/build injection; production always uses the browser's local canvas. */
export function configureRenderRuntime(value={}) { overrides=value; }
export function makeCanvas(width,height) {
  const canvas=overrides.createCanvas ? overrides.createCanvas(width,height) : typeof OffscreenCanvas!=='undefined' ? new OffscreenCanvas(width,height) : document.createElement('canvas');
  canvas.width=width;canvas.height=height;return canvas;
}
export async function decodeImage(bytes,mediaType='image/png') {
  if(overrides.loadImage){
    // Skia's SVG decoder used by the local build does not interpret CSS class
    // fills. Resolve the same safe presentation rule without changing the ZIP.
    if(mediaType==='image/svg+xml'){
      let source=new TextDecoder().decode(bytes);const fills={};
      for(const match of source.matchAll(/\.([\w-]+)\s*\{[^}]*fill\s*:\s*([^;}]+)/g))fills[match[1]]=match[2].trim();
      source=source.replace(/\sclass="([^"]+)"/g,(all,name)=>fills[name]?`${all} fill="${fills[name]}"`:all);
      bytes=new TextEncoder().encode(source);
    }
    return overrides.loadImage(bytes,mediaType);
  }
  const url=URL.createObjectURL(new Blob([bytes],{type:mediaType}));
  try{const image=new Image();image.src=url;await image.decode();return image;}finally{URL.revokeObjectURL(url);}
}
export function registeredFont(bytes,family,axes){return overrides.registerFont?.(bytes,family,axes);}
export function hasFontRuntime(){return !!overrides.registerFont;}
