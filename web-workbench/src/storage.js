const DB='starmark-staruw-workbench-v1';
function database(){return new Promise((resolve,reject)=>{const request=indexedDB.open(DB,1);request.onupgradeneeded=()=>request.result.createObjectStore('workspace');request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});}
async function transact(mode,operation){const db=await database();try{return await new Promise((resolve,reject)=>{const tx=db.transaction('workspace',mode);const request=operation(tx.objectStore('workspace'));let value;request.onsuccess=()=>{value=request.result};tx.oncomplete=()=>resolve(value);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||Error('草稿写入已中止'));});}finally{db.close();}}
export const loadWorkspace=()=>transact('readonly',s=>s.get('current'));
export const saveWorkspace=value=>transact('readwrite',s=>s.put({...value,savedAt:Date.now()},'current'));
