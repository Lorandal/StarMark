export const PHOTO_FIELDS = ['photo.make','photo.model','exif.focalLengthMm','exif.focalLength35mm','exif.aperture','exif.exposureSeconds','exif.iso','capture.localTime','gps.latitude','gps.longitude','user.signature'];
export function flattenMetadata(metadata = {}) {
  const result = {};
  for (const field of PHOTO_FIELDS) {
    const [group, key] = field.split('.');
    const value = Object.hasOwn(metadata, field) ? metadata[field] : metadata[group]?.[key];
    if (value === undefined || value === null) continue;
    if(field.startsWith('exif.')&&(!(typeof value==='number'&&Number.isFinite(value)&&value>0)||field==='exif.iso'&&!Number.isInteger(value)))continue;
    if(field.startsWith('gps.')&&(!(typeof value==='number'&&Number.isFinite(value))||Math.abs(value)>(field==='gps.latitude'?90:180)))continue;
    if(!field.startsWith('exif.')&&!field.startsWith('gps.')&&(typeof value!=='string'||!value.trim()))continue;
    result[field] = value;
  }
  return result;
}
export function decimal(value, places = 2) {
  const factor = 10 ** places;
  return ((Math.floor(Math.abs(value) * factor + .5 + 1e-10) / factor) * (value < 0 ? -1 : 1)).toFixed(places).replace(/\.?0+$/, '');
}
const positive = value => typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null;
export function formatField(field, format, metadata = {}) {
  const fields = flattenMetadata(metadata), value = fields[field];
  if (format === 'focalMm') {
    if (!['exif.focalLengthMm','exif.focalLength35mm'].includes(field)) throw new Error('E_BINDING: 焦距格式器与字段不匹配');
    const v = positive(fields['exif.focalLength35mm']) ?? positive(fields['exif.focalLengthMm']);
    return v === null ? null : `${decimal(v, 2)}mm`;
  }
  const expected = {apertureF:['exif.aperture'],shutterSeconds:['exif.exposureSeconds'],iso:['exif.iso'],gpsDms:['gps.latitude','gps.longitude'],dateTimeDots:['capture.localTime'],dateTimeSlash:['capture.localTime']};
  if (expected[format] && !expected[format].includes(field)) throw new Error('E_BINDING: 格式器与字段不匹配');
  if (value === undefined || value === null || value === '') return null;
  if (format === 'plain') return typeof value === 'number' && !Number.isFinite(value) ? null : String(value);
  if (format === 'apertureF') return positive(value) === null ? null : `f/${decimal(value,2)}`;
  if (format === 'shutterSeconds') return positive(value) === null ? null : value < 1 ? `1/${Math.floor(1/value+.5)}s` : `${decimal(value,3)}s`;
  if (format === 'iso') return positive(value) === null || !Number.isInteger(value) ? null : `ISO ${value}`;
  if (format === 'gpsDms') {
    const latitude = field === 'gps.latitude';
    if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > (latitude ? 90 : 180)) return null;
    const v = Math.floor(Math.abs(value) * 360000 + .5);
    return `${Math.floor(v/360000)}°${Math.floor(v%360000/6000)}′${(v%6000/100).toFixed(2)}″${latitude ? value < 0 ? 'S':'N' : value < 0 ? 'W':'E'}`;
  }
  if (format === 'dateTimeDots' || format === 'dateTimeSlash') {
    const m = /^(\d{4})[:./-](\d{2})[:./-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:[.Z+\-].*)?$/.exec(String(value));
    if (!m) return null;
    const p = m.slice(1,7).map(Number), d = new Date(Date.UTC(...[p[0],p[1]-1,...p.slice(2)]));
    if (d.getUTCFullYear()!==p[0]||d.getUTCMonth()!==p[1]-1||d.getUTCDate()!==p[2]||d.getUTCHours()!==p[3]||d.getUTCMinutes()!==p[4]||d.getUTCSeconds()!==p[5]) return null;
    const separator = format === 'dateTimeDots' ? '.' : '/';
    return `${m[1]}${separator}${m[2]}${separator}${m[3]} ${m[4]}:${m[5]}:${m[6]}`;
  }
  throw new Error(`E_BINDING: 未知文字格式器 ${format}`);
}
export function resolveContent(content, metadata = {}) {
  if (typeof content?.literal === 'string') return content.literal;
  return (content?.parts || []).map(p => typeof p.literal === 'string' ? p.literal : formatField(p.field,p.format,metadata)).filter(v => v !== null && v !== undefined).join(content.separator ?? '');
}
export function normalizeModel(value = '') { return String(value).trim().replace(/\s+/g,' ').replace(/[A-Z]/g,c=>c.toLowerCase()); }
export function matchModel(catalog, metadata) {
  const fields = flattenMetadata(metadata), model = normalizeModel(fields['photo.model']), make = normalizeModel(fields['photo.make']);
  return (catalog?.entries || []).find(e=>normalizeModel(e.model)===model && (e.make === undefined || normalizeModel(e.make)===make)) || null;
}
