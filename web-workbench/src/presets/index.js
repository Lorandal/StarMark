import {PRESET_META} from './definitions.js';
const base=import.meta.env?.BASE_URL||'/';
export const PRESETS=PRESET_META.map(({id,name,description})=>({id,name,description,preview:`${base}presets/${id}/preview.png`}));
const assetCache=new Map();
async function bytes(url){if(!assetCache.has(url))assetCache.set(url,fetch(url).then(async r=>{if(!r.ok)throw new Error(`模板资源加载失败：${r.status}`);return new Uint8Array(await r.arrayBuffer());}).catch(error=>{assetCache.delete(url);throw error;}));return assetCache.get(url);}
export async function createPreset(id){
  if(!PRESETS.some(p=>p.id===id))throw new Error(`未知模板 ${id}`);
  const config=JSON.parse(new TextDecoder().decode(await bytes(`${base}presets/${id}/config.json`))),files={};
  const assetMap=JSON.parse(new TextDecoder().decode(await bytes(`${base}presets/${id}/files.json`)));
  await Promise.all(Object.entries(assetMap).map(async([path,url])=>{files[path]=await bytes(`${base}presets/${url}`);}));
  return{config,manifest:null,files,baselineConfig:structuredClone(config),baselineFiles:{...files},readOnly:false,warnings:[],mode:'full',builtIn:true};
}
