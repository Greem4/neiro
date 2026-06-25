#!/usr/bin/env node
/**
 * Рендер иконки Neiro из SVG в PNG (RuStore + mipmap).
 * Запуск: node scripts/icon/generate.mjs
 */
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "../..");
const svg = readFileSync(join(__dirname, "neiro-icon.svg"));

const outputs = [
  { path: join(root, "store/icon-512.png"), size: 512 },
  { path: join(root, "store/icon-1024.png"), size: 1024 },
  { path: join(root, "app/src/main/res/mipmap-mdpi/ic_launcher.png"), size: 48 },
  { path: join(root, "app/src/main/res/mipmap-hdpi/ic_launcher.png"), size: 72 },
  { path: join(root, "app/src/main/res/mipmap-xhdpi/ic_launcher.png"), size: 96 },
  { path: join(root, "app/src/main/res/mipmap-xxhdpi/ic_launcher.png"), size: 144 },
  { path: join(root, "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"), size: 192 },
  { path: join(root, "app/src/main/res/mipmap-mdpi/ic_launcher_round.png"), size: 48 },
  { path: join(root, "app/src/main/res/mipmap-hdpi/ic_launcher_round.png"), size: 72 },
  { path: join(root, "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png"), size: 96 },
  { path: join(root, "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png"), size: 144 },
  { path: join(root, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"), size: 192 },
];

mkdirSync(join(root, "store"), { recursive: true });

for (const { path, size } of outputs) {
  mkdirSync(dirname(path), { recursive: true });
  const png = await sharp(svg).resize(size, size).png().toBuffer();
  writeFileSync(path, png);
  console.log(`wrote ${path} (${size}x${size})`);
}
