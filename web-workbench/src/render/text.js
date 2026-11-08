import {fontRuns,setFont} from './fonts.js';
import {makeCanvas} from './runtime.js';
const measureContext=()=>makeCanvas(2,2).getContext('2d');
export function prepareText(text,fontId,style,rect,resources){
  const ctx=measureContext(),font=resources.fonts[fontId];if(!font)throw new Error(`E_RESOURCE: 未定义字体 ${fontId}`);
  const size=style.size,lineHeight=style.lineHeight,spacing=style.letterSpacing||0,direction=style.direction||'ltr';
  let metrics=font.metrics(font.axes),ascent=metrics.ascent/metrics.unitsPerEm*size,descent=Math.abs(metrics.descent)/metrics.unitsPerEm*size,ratio=1;
  function ink(id,probe,probeSize,axes){const f=resources.fonts[id];for(const ch of probe)if(!f.glyph(ch.codePointAt(0)))throw new Error('E_RESOURCE: 字高探针缺字');setFont(ctx,{...f,axes:axes||f.axes},probeSize);const m=ctx.measureText(probe),h=m.actualBoundingBoxAscent+m.actualBoundingBoxDescent;if(!(h>0))throw new Error('E_RESOURCE: 字高探针没有可见字形');return h;}
  if(style.fontFit){const f=style.fontFit,reference=resources.fonts[f.referenceFontId];ratio=ink(f.referenceFontId,f.probe||'H0123456789',f.referenceSize,f.referenceAxes)/ink(fontId,f.probe||'H0123456789',size);metrics=reference.metrics(f.referenceAxes||{});ascent=metrics.ascent/metrics.unitsPerEm*f.referenceSize;descent=Math.abs(metrics.descent)/metrics.unitsPerEm*f.referenceSize;}
  const lines=text.split('\n').map(line=>{
    const runs=fontRuns(line,fontId,resources);if(direction==='rtl')runs.reverse();
    let width=0;for(const run of runs){setFont(ctx,resources.fonts[run.fontId],size*ratio,direction,spacing);run.width=ctx.measureText(run.text).width;run.x=width;width+=run.width;}return{runs,width};
  });
  const width=Math.max(0,...lines.map(l=>l.width)),height=lineHeight*lines.length;
  const shrink=style.overflow==='shrink'?Math.min(1,width?rect.width/width:1,rect.height/height):1;
  if(size*shrink+1e-9<style.minSize)throw new Error('E_TEXT_OVERFLOW: 文字缩到最小字号后仍超出边界');
  const availWidth=rect.width/shrink,availHeight=rect.height/shrink,top=style.verticalAlign==='center'?(availHeight-height)/2:style.verticalAlign==='bottom'?availHeight-height:0;
  return {width:width*shrink,height:height*shrink,draw(context){
    context.save();context.beginPath();context.rect(0,0,rect.width,rect.height);context.clip();context.scale(shrink,shrink);context.fillStyle=style.color;
    lines.forEach((line,i)=>{const left=style.align==='right'?availWidth-line.width:style.align==='center'?(availWidth-line.width)/2:0,baseline=top+i*lineHeight+(lineHeight-ascent-descent)/2+ascent;for(const run of line.runs){setFont(context,resources.fonts[run.fontId],size*ratio,direction,spacing);context.fillText(run.text,left+run.x,baseline);}});context.restore();
  }};
}
