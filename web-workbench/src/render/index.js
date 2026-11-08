import {renderScene} from './scene.js';
import {renderXiaomi} from './xiaomi.js';
import {inspectRendererSupport} from './support.js';
export {applyControls,controlOperations} from './controls.js';
export {formatField,resolveContent,flattenMetadata,matchModel,normalizeModel} from './metadata.js';
export {createGeometry,resolveBounds,roundEdge} from './geometry.js';
export {inspectRendererSupport,inspectXiaomiSupport} from './support.js';
export {createSamplePhoto} from './scene.js';
export {configureRenderRuntime} from './runtime.js';
export async function renderTemplate(canvas,pkg,options={}){
  const support=inspectRendererSupport(pkg.config);if(!support.supported){const error=new Error(support.errors.map(e=>`${e.code}: ${e.message}`).join('\n'));error.diagnostics=support.errors;throw error;}
  const result=await(pkg.config.staruw.render.engine==='scene'?renderScene:renderXiaomi)(canvas,pkg,options);result.warnings=[...support.warnings,...result.warnings];return result;
}
