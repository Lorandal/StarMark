import Ajv2020 from 'ajv/dist/2020.js';
import config from './schemas/config.schema.json' with {type:'json'};
import manifest from './schemas/manifest.schema.json' with {type:'json'};
import bundle from './schemas/bundle.schema.json' with {type:'json'};
import catalog from './schemas/model-catalog.schema.json' with {type:'json'};
import {StaruwError} from './util.js';
const ajv=new Ajv2020({strict:false,allErrors:false,validateFormats:false});
export const schemas={config,manifest,bundle,catalog};
const validators=Object.fromEntries(Object.entries(schemas).map(([key,value])=>[key,ajv.compile(value)]));
export function schema(name,value,path='') {const validate=validators[name];if(!validate(value)){const first=validate.errors[0];throw new StaruwError('E_SCHEMA',`${first.message}`,`${path}${first.instancePath}`);}return value;}
const definitions=new Map();
export function definition(name,value,code='E_BINDING') {let validate=definitions.get(name);if(!validate){validate=ajv.compile({$ref:`urn:staruw:1.0:config#/$defs/${name}`});definitions.set(name,validate);}if(!validate(value))throw new StaruwError(code,`Invalid ${name} value`,validate.errors[0].instancePath);}
