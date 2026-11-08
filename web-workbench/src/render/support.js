/** Renderer capability negotiation is deliberately independent of ZIP parsing. */
export const SUPPORTED_CAPABILITIES = new Set([
  'scene.core.v1', 'scene.model-catalog.v1', 'authoring.ten-elements.v1',
  'material.photo-blur.v1', 'xiaomi.layout.v1', 'starmark.xiaomi.v1',
]);
export const XIAOMI_VERSIONS = new Set([1, 1.58, 1.78, 1.81]);
const NATIVE_PROPERTIES = new Set(('type id orientation layout_width layout_height gravity layout_gravity margin_left margin_top background background_optional childViews display_height display_width path path_mivi support_scale degree enable optional setting_optional_type text text_upper font_ref rect_params alpha opacity visibility').split(' '));
const TEXT_BINDINGS = /^@(model|exif|time|location|custom|greeting|mix|filter)=/;

export function inspectXiaomiSupport(config) {
  const errors = [], warnings = [];
  const reject = (message, path) => errors.push({code: 'E_UNSUPPORTED', message, path});
  if (!XIAOMI_VERSIONS.has(config.version)) reject(`未登记的小米版本：${config.version}`, '/version');
  if (!Array.isArray(config.layout_group)) reject('小米模板缺少候选布局', '/layout_group');
  for(const [i,bg]of (config.wmRes?.background||[]).entries()){
    const algorithm=/@algo=([^;]+)/.exec(bg.value||'')?.[1];
    if(algorithm&&!algorithm.startsWith('gradient_color('))reject(`原厂材质需要 App 后端：${algorithm}`,`/wmRes/background/${i}`);
  }
  for(const binding of config.staruw?.xiaomi?.bindings||[]){
    if(binding.operations.includes('selectVariant')&&!['layout','background','caption'].includes(binding.property)&&!['mix.','mixText.','customizeSettings.'].some(p=>binding.property.startsWith(p)))reject(`未支持原厂选项绑定：${binding.property}`,binding.sourcePointer);
  }
  function visit(node, path) {
    if (!node || typeof node !== 'object') return reject('无效原厂节点', path);
    if (!['Layout', 'TextView', 'ImageView'].includes(node.type)) reject(`未支持的小米节点：${node.type}`, path);
    for (const key of Object.keys(node)) if (!NATIVE_PROPERTIES.has(key)) reject(`未支持的小米节点属性：${key}`, `${path}/${key}`);
    if (node.type === 'TextView' && String(node.text || '').startsWith('@') && !TEXT_BINDINGS.test(node.text)) reject(`未支持的小米绑定：${node.text}`, `${path}/text`);
    if (node.orientation && !['vertical','horizontal','overlap','none'].includes(node.orientation)) reject(`未支持的布局方向：${node.orientation}`, path);
    if(node.degree!==undefined&&![0,90,180,270,360,-90,-180,-270].includes(Number(node.degree)))reject('原厂非直角旋转暂需 App 后端',`${path}/degree`);
    for(const key of ['path','path_mivi'])if(node[key]){
      if(node[key].startsWith('pathType=')&&!/^pathType=(direct|fill|select);/.test(node[key]))reject('未支持的原厂图片选项',`${path}/${key}`);
      if(/@type_|@\{cvLens\}/.test(node[key]))reject('此原厂相机滤镜或镜头款式需要 App 适配',`${path}/${key}`);
    }
    for (const prop of ['background','foreground']) {
      if (typeof node[prop] === 'string' && node[prop].startsWith('@algo=')&&!node[prop].startsWith('@algo=gradient_color(')) reject(`需要原厂材质后端：${node[prop]}`, `${path}/${prop}`);
    }
    (node.childViews || []).forEach((c,i) => visit(c, `${path}/childViews/${i}`));
  }
  (config.layout_group || []).forEach((group,i) => {
    for (const [key, nodes] of Object.entries(group)) if (Array.isArray(nodes)) nodes.forEach((n,j) => visit(n, `/layout_group/${i}/${key}/${j}`));
  });
  if (config.version === 1.81) warnings.push({code:'W_SYNTHETIC_VERSION',message:'小米 1.81 仅通过合成夹具验证，尚无真实原包验收。'});
  return {supported: errors.length === 0, errors, warnings};
}

export function inspectRendererSupport(config) {
  const u = config?.staruw;
  const errors = [], warnings = [];
  if (!u) return {supported:false,errors:[{code:'E_UNSUPPORTED',message:'需要 StarUW 模板配置'}],warnings};
  for (const capability of u.requires || []) if (!SUPPORTED_CAPABILITIES.has(capability)) errors.push({code:'E_CAPABILITY',message:`当前网页渲染器未实现：${capability}`,capability});
  if (u.render?.engine === 'xiaomi') {
    const native = inspectXiaomiSupport(config); errors.push(...native.errors); warnings.push(...native.warnings);
  } else if (u.render?.engine !== 'scene') errors.push({code:'E_UNSUPPORTED',message:'未知渲染入口'});
  return {supported:errors.length===0,errors,warnings};
}
