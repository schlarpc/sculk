// Procedural 16x16 textures in the sculk palette, drawn once at startup and
// handed to CSS as custom properties (--tex-*). Nothing here is copied from
// the game: the textures are seeded noise in colours taken from sculk blocks,
// scaled up with pixelated rendering.

type RGB = readonly [number, number, number];

const rgb = (hex: string): RGB => [
  parseInt(hex.slice(1, 3), 16),
  parseInt(hex.slice(3, 5), 16),
  parseInt(hex.slice(5, 7), 16),
];

/** mulberry32: small, fast, deterministic. */
function random(seed: number): () => number {
  let s = seed;
  return () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pick = <T>(r: () => number, items: readonly T[]): T => items[Math.floor(r() * items.length)] as T;

class Texture {
  readonly pixels: Uint8ClampedArray<ArrayBuffer>;

  constructor(readonly size = 16) {
    this.pixels = new Uint8ClampedArray(size * size * 4);
  }

  set(x: number, y: number, c: RGB, alpha = 255): void {
    const n = this.size;
    const i = ((((y % n) + n) % n) * n + (((x % n) + n) % n)) * 4;
    this.pixels[i] = c[0];
    this.pixels[i + 1] = c[1];
    this.pixels[i + 2] = c[2];
    this.pixels[i + 3] = alpha;
  }

  /** Tileable value noise in [0, 1): a coarse random grid, bilinearly interpolated with wraparound. */
  static noise(r: () => number, size: number, cells: number): (x: number, y: number) => number {
    const grid = Array.from({ length: cells * cells }, () => r());
    const at = (gx: number, gy: number) => grid[(gy % cells) * cells + (gx % cells)] as number;
    const step = size / cells;
    return (x, y) => {
      const fx = x / step, fy = y / step;
      const x0 = Math.floor(fx), y0 = Math.floor(fy);
      const tx = fx - x0, ty = fy - y0;
      const top = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx;
      const bottom = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx;
      return top * (1 - ty) + bottom * ty;
    };
  }

  url(): string | null {
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = this.size;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.putImageData(new ImageData(this.pixels, this.size, this.size), 0, 0);
    return canvas.toDataURL("image/png");
  }
}

// Colours sampled by eye from sculk, sculk veins, sensors and shriekers.
const SCULK = ["#050f14", "#071419", "#0a1b22", "#0d232c", "#112d38", "#153643"].map(rgb);
const GLOW = { hot: rgb("#9ffaff"), bright: rgb("#2be4ef"), mid: rgb("#14a6b2"), low: rgb("#0a5f68") };
const PANEL = ["#060e12", "#071116", "#08141a", "#0a171e"].map(rgb);
const BONE = ["#b9b18c", "#c9c29e", "#d6d0b0", "#e2ddc2", "#ece8d2"].map(rgb);

/**
 * Sculk: dark cells of teal-black, with {@code spots} glowing cyan spots.
 * Spotted tiles are large ({@code size} texels) and the spots sparse and
 * uneven, so the repeat is hard to see; a spotless tile can stay small.
 */
function sculk(seed: number, size: number, spots: number): Texture {
  const t = new Texture(size);
  const r = random(seed);
  const coarse = Texture.noise(r, size, size / 4);
  const fine = Texture.noise(r, size, size / 2);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const v = coarse(x, y) * 0.55 + fine(x, y) * 0.3 + r() * 0.15;
      t.set(x, y, SCULK[Math.min(SCULK.length - 1, Math.floor(v * SCULK.length))] as RGB);
    }
  }
  for (let i = 0; i < spots; i++) {
    const x = Math.floor(r() * size), y = Math.floor(r() * size);
    // Mostly dim; only now and then a bright one.
    const roll = r();
    const [core, ring, halo] =
      roll < 0.15 ? [GLOW.bright, GLOW.mid, GLOW.low] : roll < 0.55 ? [GLOW.mid, GLOW.low, null] : [GLOW.low, null, null];
    t.set(x, y, core);
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) {
      if (ring && r() < 0.5) t.set(x + dx, y + dy, ring);
    }
    for (const [dx, dy] of [[1, 1], [-1, -1], [1, -1], [-1, 1]] as const) {
      if (halo && r() < 0.3) t.set(x + dx, y + dy, halo);
    }
  }
  return t;
}

/** Panel: near-black deep-dark stone with faint horizontal strata. */
function panel(seed: number): Texture {
  const t = new Texture();
  const r = random(seed);
  for (let y = 0; y < 16; y++) {
    const band = r() < 0.25 ? 1 : 0;
    for (let x = 0; x < 16; x++) {
      const i = Math.min(PANEL.length - 1, band + (r() < 0.3 ? 1 : 0) + (r() < 0.08 ? 1 : 0));
      t.set(x, y, PANEL[i] as RGB);
    }
  }
  return t;
}

/** Bone: the pale, porous material of shriekers and catalysts. */
function bone(seed: number): Texture {
  const t = new Texture();
  const r = random(seed);
  const n = Texture.noise(r, 16, 4);
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      const v = n(x, y) * 0.6 + r() * 0.4;
      t.set(x, y, BONE[Math.min(BONE.length - 1, Math.floor(v * BONE.length))] as RGB);
    }
  }
  for (let pores = 0; pores < 7; pores++) t.set(Math.floor(r() * 16), Math.floor(r() * 16), rgb("#9d9574"));
  return t;
}

/** Veins: branching tendrils on a transparent ground, for borders. */
function vein(seed: number): Texture {
  const t = new Texture();
  const r = random(seed);
  for (let branch = 0; branch < 3; branch++) {
    let x = Math.floor(r() * 16), y = 15;
    const len = 6 + Math.floor(r() * 8);
    for (let step = 0; step < len; step++) {
      t.set(x, y, step === len - 1 ? GLOW.bright : step > len - 3 ? GLOW.mid : pick(r, [GLOW.low, rgb("#0b3c44")]));
      if (r() < 0.6) y -= 1;
      else x += r() < 0.5 ? -1 : 1;
      if (y < 0) break;
    }
  }
  return t;
}

/**
 * Draws the textures and exposes each as --tex-NAME, with its tile size as
 * --tex-NAME-size: sculk (spotted, for the page), sculk-plain (for buttons),
 * panel, bone and vein.
 */
export function installTextures(root: HTMLElement = document.documentElement): void {
  const textures: Record<string, Texture> = {
    sculk: sculk(0x5c01c, 128, 7),
    "sculk-plain": sculk(0x5c01c, 16, 0),
    panel: panel(0xdeed),
    bone: bone(0xb07e),
    vein: vein(0x7e17),
  };
  for (const [name, texture] of Object.entries(textures)) {
    const url = texture.url();
    if (!url) continue;
    root.style.setProperty(`--tex-${name}`, `url("${url}")`);
    root.style.setProperty(`--tex-${name}-size`, `calc(${texture.size} * var(--px) * 2)`);
  }
}
