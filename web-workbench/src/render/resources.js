import {loadFonts} from './fonts.js';
import {decodeImage} from './runtime.js';
const images=new Map();
export async function loadResources(pkg,space){
  const fonts=await loadFonts(pkg,space),catalogs={};
  const resources=pkg.config.staruw.resources;
  for(const[id,resourceId]of Object.entries(space.catalogs||{})){const r=resources[resourceId];if(!pkg.files[r?.path])throw new Error(`E_RESOURCE: 缺少机型目录 ${id}`);catalogs[id]=JSON.parse(new TextDecoder().decode(pkg.files[r.path]));}
  async function image(id){const r=resources[id];if(!r)throw new Error(`E_RESOURCE: 未登记资源 ${id}`);return fileImage(r.path,r.mediaType,r.sha256);}
  async function fileImage(path,type,hash){const bytes=pkg.files[path];if(!bytes)throw new Error(`E_RESOURCE: 缺少包内图片 ${path}`);const key=hash||bytes;if(!images.has(key)){const ext=path.split('.').pop().toLowerCase();images.set(key,decodeImage(bytes,type||({svg:'image/svg+xml',webp:'image/webp',jpg:'image/jpeg',jpeg:'image/jpeg',png:'image/png'}[ext])));}return images.get(key);}
  return {...fonts,catalogs,image,fileImage};
}
