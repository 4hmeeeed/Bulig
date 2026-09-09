import './bootstrap';
import L from 'leaflet';

// Leaflet is bundled rather than pulled from a CDN: the command center must
// still render during the connectivity failures this system exists to handle.
window.L = L;

// The default marker icons resolve relative to the CSS, which breaks under
// Vite's asset hashing. Bulig draws its own priority-coloured markers instead.
delete L.Icon.Default.prototype._getIconUrl;
