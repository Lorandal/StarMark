const BLACK='#141414FF',WHITE='#FFFFFFFF';
const bounds=(reference,size,anchor=[0,0],pivot=[0,0],offset=[0,0])=>({reference,anchor,pivot,offset,size});
const style=(size,color=BLACK,align='left')=>({size,lineHeight:size*1.35,color,align,verticalAlign:'center',overflow:'shrink',minSize:Math.min(10,size),letterSpacing:0,direction:'ltr'});
const content=(fields,separator='  ')=>({parts:fields.map(([field,format])=>({field,format})),separator,missing:'omit'});
const exif=()=>content([['exif.focalLengthMm','focalMm'],['exif.aperture','apertureF'],['exif.exposureSeconds','shutterSeconds'],['exif.iso','iso']]);
const coordinates=()=>content([['gps.latitude','gpsDms'],['gps.longitude','gpsDms']]);
function modelGroup(box,color=BLACK,rotation=0){const modelStyle=style(30,color,'center');return{id:'model',type:'group',role:'model',bounds:box,rotation,replacementStyle:{fontId:'mi-sans',textStyle:modelStyle},children:[{id:'model-label',type:'model',bounds:bounds('parent',box.size,[.5,.5],[.5,.5]),catalogId:color===WHITE?'models-white':'models-black',fallbackFontId:'mi-sans',textStyle:modelStyle}]};}
function parameters(box,{size=25,color=BLACK,align='right',mark=false,markSize=56,markOffset=[0,0],textOffset=[0,0],textSize=box.size,rotation=0,separator='  ',secondary=true}={}){
  const children=[];if(mark)children.push({id:'parameter-icon',type:'image',slot:'parameterIcon',bounds:bounds('parent',[markSize,markSize],[0,0],[0,0],markOffset),resourceId:'mark-c',fit:'contain',shape:'rect'});
  const main=exif();main.separator=separator;children.push({id:'parameters-text',type:'text',slot:'parameters',bounds:bounds('parent',textSize,[0,0],[0,0],textOffset),content:main,fontId:'oxanium',textStyle:style(size,color,align)});
  if(secondary){children.push({id:'capture-time',type:'text',slot:'parameters',visible:false,bounds:bounds('parent',[textSize[0],28],[0,0],[0,0],[textOffset[0],textOffset[1]+textSize[1]+5]),content:content([['capture.localTime','dateTimeDots']]),fontId:'mi-sans',textStyle:style(18,color,align)});children.push({id:'coordinates',type:'text',slot:'parameters',visible:false,bounds:bounds('parent',[textSize[0],28],[0,0],[0,0],[textOffset[0],textOffset[1]+textSize[1]+36]),content:coordinates(),fontId:'mi-sans',textStyle:style(18,color,align)});}
  return{id:'parameters',type:'group',role:'parameters',bounds:box,rotation,children};
}
export const PRESET_META=[
  {id:'leica-6',name:'徕卡6',description:'照片内居中印记 · 底部机型与拍摄参数',uuid:'a39591bc-c423-4c6d-9801-133310396001',source:'factory-leica-67'},
  {id:'classic-2',name:'经典2',description:'四边留白 · 机型与分隔参数',uuid:'a39591bc-c423-4c6d-9801-133310396002',source:'factory-classic-18'},
  {id:'leica-watermark',name:'徕卡水印',description:'简洁白底栏 · 左机型、中央 MarkC、右参数',uuid:'a39591bc-c423-4c6d-9801-133310396003',source:'factory-leica-1'},
  {id:'leica-5',name:'徕卡5',description:'右侧竖向白边 · 旋转机型与参数',uuid:'a39591bc-c423-4c6d-9801-133310396004',source:'factory-leica-5'},
];
export function buildPresetConfig(id,resources){
  const meta=PRESET_META.find(p=>p.id===id);if(!meta)throw new Error(`未知模板 ${id}`);
  const canvas={mode:'frame',referenceShortEdge:1080,insets:{top:0,right:0,bottom:144,left:0},background:{kind:'solid',color:WHITE}};let nodes;
  if(id==='leica-6'){
    canvas.mode='overlay';canvas.insets.bottom=0;canvas.background.color='#00000000';
    nodes=[modelGroup(bounds('photo',[520,32],[.5,1],[.5,1],[0,-80]),WHITE),parameters(bounds('photo',[800,180],[.5,1],[.5,1],[0,-40]),{size:19,color:WHITE,align:'center',mark:true,markSize:60,markOffset:[370,0],textOffset:[0,145],textSize:[800,27],secondary:false})];
  }else if(id==='classic-2'){
    canvas.insets={top:48,right:48,bottom:150,left:48};
    nodes=[modelGroup(bounds('frameBottom',[360,44],[0,.5],[0,.5],[48,-5])),parameters(bounds('frameBottom',[540,58],[1,.5],[1,.5],[-48,-5]),{size:28,separator:'   |   ',textSize:[540,38]})];
  }else if(id==='leica-watermark'){
    const parameterGroup=parameters(bounds('frameBottom',[472,96],[1,.5],[1,.5],[-48,0]),{size:25,textOffset:[0,16],textSize:[472,64],secondary:true});
    // Optional shooting time and GPS share the footer's last line. Both remain
    // dynamic and initially hidden, with space reserved inside the 144U strip.
    parameterGroup.children.find(n=>n.id==='capture-time').bounds=bounds('parent',[224,24],[0,0],[0,0],[0,82]);
    parameterGroup.children.find(n=>n.id==='coordinates').bounds=bounds('parent',[232,24],[0,0],[0,0],[240,82]);
    nodes=[modelGroup(bounds('frameBottom',[380,48],[0,.5],[0,.5],[48,0])),parameterGroup,{id:'brand-mark',type:'image',bounds:bounds('frameBottom',[64,64],[.5,.5],[.5,.5]),resourceId:'mark-c',fit:'contain',shape:'rect'}];
  }else{
    canvas.insets={top:0,right:114,bottom:0,left:0};
    nodes=[modelGroup(bounds('frameRight',[300,38],[.5,1],[.5,.5],[0,-245]),BLACK,90),parameters(bounds('frameRight',[480,30],[.5,0],[.5,.5],[0,288]),{size:20,color:'#14141480',align:'left',textSize:[480,30],rotation:90,secondary:false}),{id:'brand-mark',type:'image',bounds:bounds('frameRight',[40,40],[.5,1],[.5,1],[0,-48]),resourceId:'mark-c',fit:'contain',shape:'rect',rotation:90}];
  }
  const controls=[
    {id:'show-model',label:'显示机型',type:'toggle',scope:'template',targets:[{kind:'node',id:'model',property:'visible'}]},
    {id:'show-parameters',label:'显示参数',type:'toggle',scope:'template',targets:[{kind:'node',id:'parameters',property:'visible'}]},
    {id:'model-region',label:'机型 / 签名',type:'region',scope:'template',targets:[{kind:'node',id:'model',property:'modelRegion'}],allowModes:['native','text','image'],allowTypes:['image/png','image/jpeg','image/webp','image/svg+xml'],allowFits:['contain','cover']},
    {id:'parameter-font',label:'参数字体',type:'choice',scope:'template',targets:[{kind:'node',id:'parameters-text',property:'font'}],options:[{id:'oxanium',label:'Oxanium',value:'oxanium'},{id:'mi-sans',label:'MiSans',value:'mi-sans'},{id:'lg1056',label:'LG1056',value:'lg1056'}]},
  ],defaults={'show-model':true,'show-parameters':true,'model-region':{mode:'native'},'parameter-font':'oxanium'};
  if(nodes.find(n=>n.id==='parameters').children.some(n=>n.slot==='parameterIcon')){controls.push({id:'parameter-icon',label:'参数图标',type:'image',scope:'template',targets:[{kind:'node',id:'parameters',property:'parameterIcon'}],allowTypes:['image/png','image/jpeg','image/webp','image/svg+xml'],allowShapes:['rect','circle']});defaults['parameter-icon']={mode:'native'};}
  if(nodes.some(n=>n.id==='brand-mark')){controls.push({id:'brand-image',label:'Mark 图标',type:'choice',scope:'template',targets:[{kind:'node',id:'brand-mark',property:'image'}],options:[{id:'mark-c',label:'MarkC',value:'mark-c'},{id:'mark-s',label:'MarkS',value:'mark-s'}]});defaults['brand-image']='mark-c';}
  for(const child of nodes.find(n=>n.id==='parameters').children.filter(n=>n.visible===false)){controls.push({id:`show-${child.id}`,label:child.id==='capture-time'?'显示拍摄时间':'显示坐标',type:'toggle',scope:'template',targets:[{kind:'node',id:child.id,property:'visible'}]});defaults[`show-${child.id}`]=false;}
  for(const node of nodes)for(const[property,index,label]of [['offsetX',0,'水平位置'],['offsetY',1,'垂直位置']]){const cid=`${node.id}-${property}`;controls.push({id:cid,label,type:'number',scope:'template',targets:[{kind:'node',id:node.id,property}],min:-2000,max:2000,step:1});defaults[cid]=node.bounds.offset[index];}
  return{format:'com.realize.starmark.watermark',staruw:{standard:'StarUW',protocolVersion:'1.0.0',identity:{id:meta.uuid,revision:1},metadata:{name:meta.name,brand:'custom',category:id==='classic-2'?'classic':'leica',preview:'preview.png',previewKind:'rendered',author:'StarMark',description:meta.description},render:{engine:'scene',profile:'scene.core.v1'},requires:['scene.core.v1','scene.model-catalog.v1'],resources,controls,defaults,scene:{canvas,fonts:{'mi-sans':{resourceId:'font-mi-sans',axes:{wght:380},fallbackFontIds:[]},oxanium:{resourceId:'font-oxanium',axes:{wght:500},fallbackFontIds:['mi-sans']},lg1056:{resourceId:'font-lg1056',axes:{},fallbackFontIds:['mi-sans']}},catalogs:{'models-black':'catalog-black','models-white':'catalog-white'},materials:{},nodes},authoring:{profile:'authoring.ten-elements.v1',referencePhotoAspect:4/3,editorState:Object.fromEntries(nodes.map(n=>[n.id,{name:n.id==='model'?'机型功能区':n.id==='parameters'?'参数功能区':'Mark 图标',locked:false}]))},extensions:{'com.realize.starmark.web':{preset:id,geometryReference:meta.source}}}};
}
