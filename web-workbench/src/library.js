/** Resolve immutable imported identities; unsaved design edits never replace them. */
export function reconcileImports(existing,incoming){
 const identities=new Map(),accepted=[],duplicates=[],diagnostics=[];
 const key=p=>p.config.staruw.protocolVersion==='1.0.0'&&p.manifest?`${p.config.staruw.identity.id}/${p.config.staruw.identity.revision}`:null;
 for(const item of existing){const k=key(item.pkg);if(k)identities.set(k,{digest:item.pkg.manifest.payloadDigest,libraryKey:item.key});}
 for(const pkg of incoming){const k=key(pkg),record=k&&identities.get(k);if(record){if(record.digest!==pkg.manifest.payloadDigest)diagnostics.push({code:'E_ID_CONFLICT',message:`${pkg.config.staruw.metadata.name} 的身份和修订号与现有模板冲突，已保留当前模板。`});else duplicates.push(record.libraryKey);continue;}accepted.push(pkg);if(k)identities.set(k,{digest:pkg.manifest.payloadDigest,libraryKey:null});}
 return {accepted,duplicates,diagnostics};
}
