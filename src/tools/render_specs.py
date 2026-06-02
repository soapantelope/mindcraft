#!/usr/bin/env python3
"""
render_specs.py — render all bundled WorldSpec JSON files into one combined PNG.

Each spec gets one row with two panels:
  [Elevation + hillshade]  [Biome zones]

Run from repo root:
  /opt/homebrew/bin/python3 src/tools/render_specs.py
"""

import os, json, glob, math
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.colors import LinearSegmentedColormap, LightSource, ListedColormap, TwoSlopeNorm

SPEC_DIR   = "src/main/resources/data/mindcraft/specs"
EVAL_DIR   = "src/eval"
OUT_FILE   = "src/eval/all_specs.png"
GRID_SIZE  = 256          # smaller for the combined view — still readable
TILE_X     = 0
TILE_Z     = 0
WORLD_SEED = 42

TERRAIN_CMAP = LinearSegmentedColormap.from_list("terrain_mc", [
    (0.00, "#001040"), (0.20, "#0a3080"), (0.42, "#1a5fa8"),
    (0.50, "#c9b06a"), (0.55, "#5d8a3c"), (0.70, "#3a6b28"),
    (0.80, "#7a5c3a"), (0.90, "#9e8f83"), (0.97, "#d4d0cc"), (1.00, "#ffffff"),
])

# Minecraft biome → colour mapping (best-effort, unknown biomes get grey)
BIOME_COLORS = {
    # oceans / water
    "ocean":                    "#1a3a6b",
    "deep_ocean":               "#0d1f3c",
    "cold_ocean":               "#1a4a7a",
    "frozen_ocean":             "#8ab4cc",
    "warm_ocean":               "#2060a0",
    "lukewarm_ocean":           "#1a5090",
    "river":                    "#2a6090",
    "frozen_river":             "#a0c8e0",
    # beaches / coasts
    "beach":                    "#d4c47a",
    "snowy_beach":              "#e0dcd0",
    "stony_shore":              "#8a8a8a",
    # plains / grasslands
    "plains":                   "#80a848",
    "sunflower_plains":         "#a0c040",
    "meadow":                   "#70c070",
    "cherry_grove":             "#e88ab0",
    # forests
    "forest":                   "#306830",
    "birch_forest":             "#5a9050",
    "old_growth_birch_forest":  "#4a8040",
    "dark_forest":              "#1e4820",
    "flower_forest":            "#60a840",
    # taiga
    "taiga":                    "#3a6848",
    "old_growth_spruce_taiga":  "#2a5838",
    "old_growth_pine_taiga":    "#2a4830",
    "snowy_taiga":              "#7090a0",
    # snowy
    "snowy_plains":             "#d0e0e8",
    "snowy_slopes":             "#c8d8e0",
    "ice_spikes":               "#c0d8f0",
    "grove":                    "#90a8b0",
    "frozen_peaks":             "#e0ecf4",
    "jagged_peaks":             "#d0dce8",
    "stony_peaks":              "#9a9090",
    # mountains
    "windswept_hills":          "#7a8878",
    "windswept_gravelly_hills": "#8a9080",
    "windswept_forest":         "#506050",
    "windswept_savanna":        "#a09040",
    # jungle
    "jungle":                   "#286018",
    "sparse_jungle":            "#488830",
    "bamboo_jungle":            "#207010",
    # savanna
    "savanna":                  "#b0a040",
    "savanna_plateau":          "#c0b060",
    # desert / badlands
    "desert":                   "#d8c870",
    "badlands":                 "#c07840",
    "eroded_badlands":          "#d08040",
    "wooded_badlands":          "#987048",
    # swamp / wetlands
    "swamp":                    "#4a6840",
    "mangrove_swamp":           "#3a7050",
    # mushroom
    "mushroom_fields":          "#b060b0",
    # nether (shouldn't appear but just in case)
    "nether_wastes":            "#7a1010",
    # special
    "pale_garden":              "#c8c8c0",
    "cherry_meadow":            "#e89ab8",
}

def biome_color(name: str):
    c = BIOME_COLORS.get(name)
    if c: return c
    # Fuzzy match for unrecognised biomes
    n = name.lower()
    if "ocean" in n or "river" in n: return "#1a4a80"
    if "snow" in n or "frozen" in n or "ice" in n: return "#c8dce8"
    if "peak" in n or "mountain" in n: return "#c0c8d0"
    if "forest" in n: return "#306830"
    if "taiga" in n: return "#3a6848"
    if "jungle" in n: return "#287018"
    if "desert" in n or "sand" in n: return "#d8c870"
    if "badland" in n or "mesa" in n: return "#c07840"
    if "swamp" in n or "mangrove" in n: return "#4a6840"
    if "savanna" in n: return "#b0a040"
    if "plain" in n or "meadow" in n: return "#80a848"
    return "#888888"

def hex_to_rgb(h: str):
    h = h.lstrip('#')
    return tuple(int(h[i:i+2], 16)/255.0 for i in (0, 2, 4))

# ─── Noise (matches Noise.java) ───────────────────────────────────────────────

_PERM_CACHE: dict = {}

def _build_perm(seed: int):
    A = 6364136223846793005; B = 1442695040888963407; M = 1 << 64
    k = seed & 0xFFFF
    state = (0x9E3779B97F4A7C15 if k == 0 else (k * 0x9E3779B97F4A7C15) % M)
    n = 512
    gx = np.empty(n, np.float32); gy = np.empty(n, np.float32)
    for i in range(n):
        state = (state * A + B) % M
        u = ((state >> 11) & ((1 << 53) - 1)) / (1 << 53)
        a = u * 2 * math.pi; gx[i] = math.cos(a); gy[i] = math.sin(a)
    base = list(range(n))
    for i in range(n - 1, 0, -1):
        state = (state * A + B) % M
        j = int((state >> 1) % (i + 1))
        base[i], base[j] = base[j], base[i]
    perm = np.array(base * 2, np.int32)
    return gx, gy, perm, n - 1

def _perm(seed: int):
    k = seed & 0xFFFF
    if k not in _PERM_CACHE:
        _PERM_CACHE[k] = _build_perm(seed)
    return _PERM_CACHE[k]

def perlin(xx, yy, seed: int):
    gx, gy, perm, mask = _perm(seed)
    xi  = np.floor(xx).astype(np.int32) & mask
    yi  = np.floor(yy).astype(np.int32) & mask
    xf  = (xx - np.floor(xx)).astype(np.float32)
    yf  = (yy - np.floor(yy)).astype(np.float32)
    xi1 = (xi+1) & mask; yi1 = (yi+1) & mask
    u = xf*xf*xf*(xf*(xf*6-15)+10); v = yf*yf*yf*(yf*(yf*6-15)+10)
    i00=perm[perm[xi]+yi]; i10=perm[perm[xi1]+yi]
    i01=perm[perm[xi]+yi1]; i11=perm[perm[xi1]+yi1]
    n00=gx[i00]*xf    +gy[i00]*yf;     n10=gx[i10]*(xf-1)+gy[i10]*yf
    n01=gx[i01]*xf    +gy[i01]*(yf-1); n11=gx[i11]*(xf-1)+gy[i11]*(yf-1)
    return (n00+u*(n10-n00)) + v*((n01+u*(n11-n01))-(n00+u*(n10-n00)))

def fbm(xx, yy, octaves, persistence, lacunarity, seed):
    out=np.zeros_like(xx,np.float64); amp=1.0; total=0.0; f=1.0
    for i in range(int(octaves)):
        out+=amp*perlin(xx*f,yy*f,seed+i*97); total+=amp; amp*=persistence; f*=lacunarity
    return (out/total).astype(np.float32)

def ridged(xx, yy, octaves, persistence, lacunarity, sharpness, seed):
    out=np.zeros_like(xx,np.float64); amp=1.0; total=0.0; f=1.0
    for i in range(int(octaves)):
        n=np.clip(1-np.abs(perlin(xx*f,yy*f,seed+i*97)),0,None)**float(sharpness)
        out+=amp*n; total+=amp; amp*=persistence; f*=lacunarity
    return (out/total).astype(np.float32)

def billow(xx, yy, octaves, persistence, lacunarity, seed):
    out=np.zeros_like(xx,np.float64); amp=1.0; total=0.0; f=1.0
    for i in range(int(octaves)):
        out+=amp*(np.abs(perlin(xx*f,yy*f,seed+i*97))*2-1)
        total+=amp; amp*=persistence; f*=lacunarity
    return (out/total).astype(np.float32)

def domain_warp(xx, yy, octaves, warp, seed):
    wx=fbm(xx,yy,octaves,0.5,2.0,seed+200)*warp
    wy=fbm(xx,yy,octaves,0.5,2.0,seed+400)*warp
    return fbm(xx+wx,yy+wy,octaves,0.5,2.0,seed)

def domain_warp_ridged(xx, yy, octaves, warp, sharpness, persistence, lacunarity, seed):
    wx=fbm(xx,yy,octaves,0.5,2.0,seed+200)*warp
    wy=fbm(xx,yy,octaves,0.5,2.0,seed+400)*warp
    return ridged(xx+wx,yy+wy,octaves,persistence,lacunarity,sharpness,seed)

# ─── Grid helpers ─────────────────────────────────────────────────────────────

def tile_grid(N, tileX, tileZ):
    inv = 1.0/(N-1)
    xs = (tileX + np.arange(N)*inv).astype(np.float32)
    zs = (tileZ + np.arange(N)*inv).astype(np.float32)
    return np.meshgrid(xs, zs)

def global_fbm_grid(freq, oct, per, lac, seed, N, tileX, tileZ):
    xx, zz = tile_grid(N, tileX, tileZ)
    return ((fbm(xx*freq,zz*freq,oct,per,lac,seed)+1)*0.5).clip(0,1).astype(np.float32)

def smooth01(v, lo, hi):
    if hi <= lo: return np.where(v>=hi,1.0,0.0).astype(np.float32)
    t = np.clip((v-lo)/(hi-lo),0,1); return (t*t*(3-2*t)).astype(np.float32)

# ─── Noise layer ──────────────────────────────────────────────────────────────

def make_noise_layer(layer, seed, N, tileX, tileZ):
    xx, zz = tile_grid(N, tileX, tileZ)
    fr=float(layer.get('frequency',4.0)); oct=int(layer.get('octaves',7))
    per=float(layer.get('persistence',0.5)); lac=float(layer.get('lacunarity',2.0))
    sh=float(layer.get('sharpness',2.5)); wr=float(layer.get('warp_strength',0.45))
    ns=int(layer.get('n_steps',7)); jit=float(layer.get('jitter',0.022))
    mode=layer.get('mode','fbm')
    if mode=='fbm':
        return ((fbm(xx*fr,zz*fr,oct,per,lac,seed)+1)*0.5).clip(0,1).astype(np.float32)
    elif mode=='ridged':
        return ridged(xx*fr,zz*fr,oct,per,lac,sh,seed).clip(0,1).astype(np.float32)
    elif mode=='billow':
        return ((billow(xx*fr,zz*fr,oct,per,lac,seed)+1)*0.5).clip(0,1).astype(np.float32)
    elif mode=='stepped':
        e=((fbm(xx*fr,zz*fr,oct,per,lac,seed)+1)*0.5).clip(0,1)
        jg=global_fbm_grid(8.0,3,0.4,2.0,seed+700,N,tileX,tileZ)
        return (np.floor(e*ns)/ns+jg*jit).clip(0,1).astype(np.float32)
    elif mode=='domain_warp':
        return ((domain_warp(xx*fr,zz*fr,oct,wr*fr,seed)+1)*0.5).clip(0,1).astype(np.float32)
    elif mode=='domain_warp_ridged':
        return domain_warp_ridged(xx*fr,zz*fr,oct,wr*fr,sh,per,lac,seed).clip(0,1).astype(np.float32)
    else:
        return ((fbm(xx*fr,zz*fr,oct,per,lac,seed)+1)*0.5).clip(0,1).astype(np.float32)

def layer_mask(layer, seed, N, tileX, tileZ):
    m = layer.get('mask')
    if m is None: return None
    grid=global_fbm_grid(m.get('frequency',1.5),m.get('octaves',4),
                         m.get('persistence',0.5),2.0,seed+313,N,tileX,tileZ)
    t=m.get('threshold',0.5); sf=max(m.get('softness',0.05),1e-3)
    w=np.clip((grid-(t-sf))/(2*sf),0,1)
    return (1-w) if m.get('invert',False) else w

# ─── Spatial features ─────────────────────────────────────────────────────────

def _contour_dist(xx, zz, freq, h, oct, warp, seed):
    """|v| / |∇v| — first-order distance (tile units) to the field's zero-contour."""
    v   = domain_warp( xx     *freq,  zz     *freq, oct, warp, seed)
    vpx = domain_warp((xx+h)  *freq,  zz     *freq, oct, warp, seed)
    vmx = domain_warp((xx-h)  *freq,  zz     *freq, oct, warp, seed)
    vpz = domain_warp( xx     *freq, (zz+h)  *freq, oct, warp, seed)
    vmz = domain_warp( xx     *freq, (zz-h)  *freq, oct, warp, seed)
    dvx = (vpx - vmx) / (2*h)
    dvz = (vpz - vmz) / (2*h)
    grad = np.sqrt(dvx*dvx + dvz*dvz) + 1e-4
    return np.abs(v) / grad

def feat_rivers(elev, feat, seed, sea_level, range_lo, range_hi, tileX, tileZ, N):
    """Mirrors TerrainPipeline.continuousRivers — gradient-normalised distance
    to a domain-warped zero-contour, carved down to sea level. Peak-fade gate is
    relative to the spec's own elevation band so elevated plains still get rivers.
    tributary_strength controls whether secondary contour networks are blended in."""
    rseed  = seed + 1300
    fr     = float(feat.get('river_freq',   0.55))
    warp   = float(feat.get('river_warp',   0.5))
    oct    = max(2, int(feat.get('river_octaves', 4)))
    rWidth = max(1e-5, float(feat.get('river_width', 0.0024)))
    bWidth = max(1e-5, float(feat.get('bank_width',  0.018)))
    tribS  = float(np.clip(feat.get('tributary_strength', 0.0), 0, 1))

    bed     = float(np.clip(sea_level - 0.035, 0, 1))
    span    = max(1e-3, range_hi - range_lo)
    gate_lo = range_lo + 0.45 * span
    gate_hi = range_lo + 0.70 * span

    xx, zz = tile_grid(N, tileX, tileZ)
    hh = 1.0 / (N - 1)
    dist1 = _contour_dist(xx, zz, fr, hh, oct, warp, rseed)
    if tribS > 1e-3:
        dist2 = _contour_dist(xx, zz, fr*2.3, hh, oct, warp, rseed+777)
        dist  = np.minimum(dist1, dist2 * (2.0 - tribS))
    else:
        dist = dist1

    chan   = 1 - smooth01(dist, rWidth, rWidth + bWidth)
    gate   = 1 - smooth01(elev, gate_lo, gate_hi)
    amount = chan * gate

    target = np.minimum(elev, bed)
    new_e  = (elev + (target - elev) * amount).clip(0, 1).astype(np.float32)
    return new_e, (amount > 0.85)

# ─── Full tile generation ─────────────────────────────────────────────────────

def generate_tile(spec, world_seed, tileX=0, tileZ=0, N=256):
    base_seed=int((world_seed ^ spec.get('seed_offset',0)) & 0x7FFFFFFF)

    # Base elevation
    if 'noise_layers' in spec:
        accum=np.zeros((N,N),np.float32); weights=np.zeros((N,N),np.float32); adds=[]
        for li,layer in enumerate(spec['noise_layers']):
            ls=base_seed+li*1009; e=make_noise_layer(layer,ls,N,tileX,tileZ)
            wc=float(layer.get('weight',1.0)); mk=layer_mask(layer,ls,N,tileX,tileZ)
            wp=wc*(mk if mk is not None else 1.0)
            if layer.get('blend','average')=='add': adds.append((e,wp))
            else: accum+=e*wp; weights+=wp
        elev=(accum/np.maximum(weights,1e-6)).clip(0,1).astype(np.float32)
        for e,w in adds: elev+=e*w
        elev=elev.clip(0,1)
    elif 'elevation_profile' in spec:
        elev=make_noise_layer(spec['elevation_profile'],base_seed,N,tileX,tileZ)
    else:
        elev=np.full((N,N),0.4,np.float32)

    er=spec.get('elevation_range',[0.0,1.0]); lo,hi=float(er[0]),float(er[1])
    if lo!=0.0 or hi!=1.0:
        elev=(elev*(hi-lo)+lo).clip(0,1).astype(np.float32)

    river_mask=None
    for feat in spec.get('spatial_features',[]):
        t=feat.get('type')
        if t=='sea_threshold':
            elev=(elev+float(feat.get('offset',-0.46))).clip(0,1)
        elif t=='stretch':
            tl,th=float(feat['to'][0]),float(feat['to'][1])
            elev=(elev*(th-tl)+tl).clip(0,1).astype(np.float32)
        elif t=='mesa_step':
            ns=int(feat.get('n_steps',6)); jit=float(feat.get('jitter',0.02))
            jg=global_fbm_grid(8.0,3,0.4,2.0,base_seed+700,N,tileX,tileZ)
            elev=(np.floor(elev*ns)/ns+jg*jit).clip(0,1).astype(np.float32)
        elif t=='coastal_smooth':
            sl=float(feat.get('sea_level',spec.get('sea_level',0.3)))
            wd=float(feat.get('width',0.08)); st=float(feat.get('strength',0.6))
            delta=elev-sl; pull=np.clip(1-np.abs(delta)/max(wd,1e-6),0,None)*st
            elev=elev-delta*pull
        elif t=='dune_ridges':
            dd=float(feat.get('direction_deg',270)); sp=float(feat.get('spacing',0.04))
            amp=float(feat.get('amplitude',0.04))
            mk=global_fbm_grid(2.5,3,0.5,2.0,base_seed+81,N,tileX,tileZ)
            xx,zz=tile_grid(N,tileX,tileZ)
            theta=math.radians(dd)
            proj=(math.cos(theta)*xx+math.sin(theta)*zz)/max(sp,1e-4)
            r=(np.sin(2*np.pi*proj)*0.5+0.5)**2
            elev=(elev+amp*(r-0.5)*mk).clip(0,1).astype(np.float32)
        elif t=='river':
            _er=spec.get('elevation_range',[0.0,1.0])
            elev,river_mask=feat_rivers(elev,feat,base_seed,
                                        float(spec.get('sea_level',0.3)),
                                        float(_er[0]),float(_er[1]),tileX,tileZ,N)

    return elev, river_mask

def assign_biomes(elev, spec, river_mask):
    """Returns an RGB image (H,W,3) of biome colours."""
    zones = spec.get('biome_zones', [])
    river_biome = spec.get('river_biome', 'river')
    sea_level = float(spec.get('sea_level', 0.3))
    N = elev.shape[0]
    img = np.zeros((N, N, 3), np.float32)

    # Build sorted zone list
    sorted_zones = sorted(zones, key=lambda z: z['elev_max'])
    fallback_biome = sorted_zones[-1]['biome'] if sorted_zones else 'plains'

    # Default: deep ocean for below sea level
    ocean_col = np.array(hex_to_rgb(BIOME_COLORS.get('ocean', '#1a3a6b')), np.float32)
    img[:, :] = ocean_col

    for zone in sorted_zones:
        col = np.array(hex_to_rgb(biome_color(zone['biome'])), np.float32)
        mask = elev <= zone['elev_max']
        # Only paint if not already painted by a lower zone
        pass  # We paint from bottom up

    # Paint zones from bottom upward (each zone covers elev_max and below,
    # but we only want the *first* matching zone, so paint high→low)
    # Actually: for each pixel, find the first zone where elev <= elev_max.
    # Faster: vectorised pass from highest zone down, overwrite each time.
    biome_img = np.zeros((N, N, 3), np.float32)
    # Start with fallback
    fb_col = np.array(hex_to_rgb(biome_color(fallback_biome)), np.float32)
    biome_img[:] = fb_col

    # Apply zones high→low so lower zones overwrite higher ones
    for zone in reversed(sorted_zones):
        col = np.array(hex_to_rgb(biome_color(zone['biome'])), np.float32)
        biome_img[elev <= zone['elev_max']] = col

    # River overlay
    if river_mask is not None:
        rcol = np.array(hex_to_rgb(biome_color(river_biome)), np.float32)
        biome_img[river_mask] = rcol

    return biome_img, sorted_zones

# ─── Combined render ──────────────────────────────────────────────────────────

def render_all(specs_data):
    n_specs = len(specs_data)
    # Layout: n_specs rows × 2 cols (elev | biome), plus spec name label
    panel_px = GRID_SIZE  # each panel is square
    col_w = panel_px / 100.0   # inches
    row_h = panel_px / 100.0
    label_w = 1.6             # inches for the row label
    legend_w = 1.4            # inches for biome legend
    fig_w = label_w + 2 * col_w + legend_w + 0.4
    fig_h = n_specs * row_h + 0.6   # +0.6 for column headers

    fig = plt.figure(figsize=(fig_w, fig_h), dpi=120)
    fig.patch.set_facecolor('#111122')

    header_h_frac  = 0.4 / fig_h
    row_h_frac     = row_h / fig_h
    label_w_frac   = label_w / fig_w
    col_w_frac     = col_w / fig_w
    legend_w_frac  = legend_w / fig_w
    gap            = 0.01       # fractional gap between panels
    left_pad       = 0.01

    ls = LightSource(azdeg=315, altdeg=40)

    # Column headers
    for ci, title in enumerate(["Elevation", "Biomes"]):
        x = left_pad + label_w_frac + ci*(col_w_frac + gap) + col_w_frac/2
        y = 1.0 - header_h_frac/2
        fig.text(x, y, title, ha='center', va='center',
                 color='#cccccc', fontsize=9, fontweight='bold')

    for ri, (spec, elev, river_mask, biome_img, zones) in enumerate(specs_data):
        # Row baseline (top of row, counting from top)
        row_top  = 1.0 - header_h_frac - ri * row_h_frac
        row_bot  = row_top - row_h_frac
        row_mid  = (row_top + row_bot) / 2

        # ── Row label ──
        fig.text(left_pad + label_w_frac/2, row_mid,
                 spec.get('name', '?'), ha='center', va='center',
                 color='white', fontsize=7, fontweight='bold')

        # ── Elevation panel ──
        shade = ls.hillshade(elev, vert_exag=4.0, dx=1.0, dy=1.0)
        sea   = float(spec.get('sea_level', 0.3))
        ocean_pct = float((elev < sea).mean() * 100)

        ax_e = fig.add_axes([left_pad + label_w_frac, row_bot + gap*0.5,
                             col_w_frac - gap, row_h_frac - gap])
        # Anchor the colormap's ocean/land boundary (0.5) to this spec's sea level
        # so colours are accurate regardless of where sea level sits in [0,1].
        sea_clamped = max(0.02, min(0.98, sea))
        elev_norm = TwoSlopeNorm(vmin=0.0, vcenter=sea_clamped, vmax=1.0)
        ax_e.imshow(elev, cmap=TERRAIN_CMAP, norm=elev_norm, interpolation='lanczos')
        ax_e.imshow(shade, cmap='gray', alpha=0.30, vmin=0, vmax=1, interpolation='lanczos')
        ax_e.set_title(f"ocean {ocean_pct:.0f}%", fontsize=6, pad=2, color='#aaaaaa')
        ax_e.axis('off')

        # ── Biome panel ──
        ax_b = fig.add_axes([left_pad + label_w_frac + col_w_frac + gap*0.5,
                             row_bot + gap*0.5,
                             col_w_frac - gap, row_h_frac - gap])
        ax_b.imshow(biome_img, interpolation='nearest')
        ax_b.axis('off')

        # ── Biome legend (right side of biome panel) ──
        legend_x = left_pad + label_w_frac + 2*col_w_frac + gap + 0.01
        legend_y = row_bot + gap*0.5
        legend_h = row_h_frac - gap
        ax_leg = fig.add_axes([legend_x, legend_y, legend_w_frac - 0.02, legend_h])
        ax_leg.axis('off')
        ax_leg.set_facecolor('#111122')

        legend_biomes = [z['biome'] for z in zones]
        if river_mask is not None:
            legend_biomes.append(spec.get('river_biome', 'river'))
        legend_biomes = list(dict.fromkeys(legend_biomes))  # deduplicate, keep order

        n_leg = len(legend_biomes)
        for bi, bname in enumerate(legend_biomes):
            col = hex_to_rgb(biome_color(bname))
            ypos = 1.0 - (bi + 0.5) / max(n_leg, 1)
            ax_leg.add_patch(plt.Rectangle((0.0, ypos - 0.35/max(n_leg,1)),
                                           0.18, 0.70/max(n_leg,1),
                                           color=col, transform=ax_leg.transAxes,
                                           clip_on=False))
            label = bname.replace('_', ' ')
            ax_leg.text(0.25, ypos, label, transform=ax_leg.transAxes,
                        va='center', ha='left', fontsize=max(4, min(6, 52//n_leg)),
                        color='#dddddd')

    fig.savefig(OUT_FILE, bbox_inches='tight', facecolor=fig.get_facecolor(), dpi=120)
    plt.close(fig)
    print(f"Saved: {OUT_FILE}")

# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    os.makedirs(EVAL_DIR, exist_ok=True)
    # Remove old PNGs
    for old in glob.glob(os.path.join(EVAL_DIR, '*.png')):
        os.remove(old)

    spec_files = sorted(p for p in glob.glob(os.path.join(SPEC_DIR, '*.json'))
                        if not os.path.basename(p).startswith('_'))

    print(f"Generating {len(spec_files)} specs at {GRID_SIZE}×{GRID_SIZE}...\n")
    specs_data = []
    for path in spec_files:
        name = os.path.basename(path).replace('.json', '')
        with open(path) as f:
            spec = json.load(f)
        print(f"  {spec.get('name', name):<32}", end=' ', flush=True)
        elev, river_mask = generate_tile(spec, WORLD_SEED, TILE_X, TILE_Z, GRID_SIZE)
        biome_img, zones  = assign_biomes(elev, spec, river_mask)
        specs_data.append((spec, elev, river_mask, biome_img, zones))
        sea = float(spec.get('sea_level', 0.3))
        ocean = float((elev < sea).mean() * 100)
        riv = float(river_mask.mean() * 100) if river_mask is not None else 0.0
        print(f"ocean {ocean:5.1f}%  river {riv:4.1f}%")

    print("\nCompositing...", end=' ', flush=True)
    render_all(specs_data)

if __name__ == '__main__':
    os.chdir(os.path.join(os.path.dirname(__file__), '..', '..'))
    main()
