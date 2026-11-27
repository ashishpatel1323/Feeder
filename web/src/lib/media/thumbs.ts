/**
 * The picture on each row of Books > Player, in this order:
 *  1. the thumbnail kept with a moved YouTube download (keepThumb in library.ts),
 *  2. a picture next to the file in Dropbox with the same name (MediaFile.thumb),
 *  3. the cover art inside the file (MP3, M4A, MP4…),
 *  4. for a video that plays in this browser, a frame from about a tenth of the way in.
 * What is found is kept small in this browser (db.mediaThumbs), so each file is looked at once.
 * When none is found the row keeps its coloured tile, and the file is tried again after a week or
 * once it is on this device. Same order as the Android app (model/media/MediaThumbs.kt).
 */
import { useEffect, useMemo } from "react";
import { useLiveQuery } from "dexie-react-hooks";
import { db, type MediaFile, type MediaThumb } from "../db";
import { downloadStream } from "../dropbox";
import { dropboxReader } from "../audiobooks/library";
import { blobReader, readTags, type RangeReader } from "../audiobooks/tags";
import {
    keptThumbName,
    localByName,
    localMediaFile,
    mediaUrl,
    playsHere,
} from "./library";

export const RETRY_MS = 7 * 24 * 60 * 60 * 1000;
export const SIDECAR_RETRY_MS = 60 * 60 * 1000;
const SIDE = 360;

/** Whether to look for a file's picture (again), given what was found before. */
export function shouldTry(
    known: MediaThumb | undefined,
    m: Pick<MediaFile, "local" | "thumb">,
    now = Date.now(),
): boolean {
    if (!known) return true;
    // A picture put next to the file in Dropbox wins over what was found before
    // (one that failed to load, offline say, is tried again an hour later)
    if (
        m.thumb &&
        known.from !== m.thumb &&
        (!known.sideFailedAt || now - known.sideFailedAt > SIDECAR_RETRY_MS)
    )
        return true;
    if (known.url) return false;
    if (m.local && !known.local) return true;
    return now - known.at > RETRY_MS;
}

/** Where in a video to take its frame: about a tenth of the way in, at least 1 s and at most a minute. */
export function frameAt(duration: number | null | undefined): number {
    if (!duration || !Number.isFinite(duration) || duration <= 0) return 5;
    if (duration < 2) return duration / 2;
    return Math.min(60, Math.max(1, duration / 10));
}

/** A picture scaled down to [SIDE] px on its long side, as a JPEG data URL. */
function shrink(
    src: CanvasImageSource & { width: number; height: number },
    w = src.width,
    h = src.height,
): string | null {
    if (!w || !h) return null;
    const scale = Math.min(1, SIDE / Math.max(w, h));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(w * scale));
    canvas.height = Math.max(1, Math.round(h * scale));
    canvas.getContext("2d")!.drawImage(src, 0, 0, canvas.width, canvas.height);
    // Throws when a cross-origin video tainted the canvas
    return canvas.toDataURL("image/jpeg", 0.82);
}

async function pictureOf(blob: Blob): Promise<string | null> {
    const bmp = await createImageBitmap(blob).catch(() => null);
    if (!bmp) return null;
    try {
        return shrink(bmp);
    } finally {
        bmp.close();
    }
}

/** A frame of the video at [src], or null when it can't be read (a codec this browser lacks, no CORS). */
export function videoFrame(
    src: string,
    duration?: number | null,
): Promise<string | null> {
    return new Promise((resolve) => {
        const v = document.createElement("video");
        v.muted = true;
        v.preload = "auto";
        v.playsInline = true;
        v.crossOrigin = "anonymous";
        let settled = false;
        const done = (url: string | null) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            v.removeAttribute("src");
            v.load();
            resolve(url);
        };
        const timer = setTimeout(() => done(null), 30_000);
        v.addEventListener("loadedmetadata", () => {
            v.currentTime = frameAt(
                Number.isFinite(v.duration) ? v.duration : duration,
            );
        });
        v.addEventListener("seeked", () => {
            try {
                done(shrink(v, v.videoWidth, v.videoHeight));
            } catch {
                done(null);
            }
        });
        v.addEventListener("error", () => done(null));
        v.src = src;
    });
}

async function readerOf(m: MediaFile): Promise<RangeReader | null> {
    const file = await localMediaFile(m);
    if (file) return blobReader(file);
    return m.dropboxPath ? dropboxReader(m.dropboxPath, m.size) : null;
}

interface Found {
    url: string | null;
    /** The Dropbox picture it came from (the kept one is the same picture once uploaded) */
    from: string | null;
    /** Whether loading the Dropbox picture failed */
    sideFailed: boolean;
}

/** Looks for [m]'s picture in the order above. */
async function find(m: MediaFile): Promise<Found> {
    const kept = await localByName(keptThumbName(m.id));
    if (kept) {
        const url = await pictureOf(kept);
        if (url) return { url, from: m.thumb ?? null, sideFailed: false };
    }
    let sideFailed = false;
    if (m.thumb) {
        try {
            const { body } = await downloadStream(m.thumb);
            const url = await pictureOf(await new Response(body).blob());
            if (url) return { url, from: m.thumb, sideFailed: false };
        } catch (e) {
            console.warn("Could not read the picture next to", m.name, e);
        }
        sideFailed = true;
    }
    return { url: await fromFile(m), from: null, sideFailed };
}

/** The cover art inside the file, else (a video) a frame. */
async function fromFile(m: MediaFile): Promise<string | null> {
    try {
        const r = await readerOf(m);
        const tags = r ? await readTags(m.name, r) : null;
        if (tags?.cover) {
            const url = await pictureOf(
                new Blob([tags.cover.data.slice().buffer as ArrayBuffer], {
                    type: tags.cover.type,
                }),
            );
            if (url) return url;
        }
    } catch (e) {
        console.warn("Could not read the cover art of", m.name, e);
    }
    if (m.kind === "video" && playsHere(m.name)) {
        try {
            return await videoFrame(await mediaUrl(m), m.duration);
        } catch (e) {
            console.warn("Could not take a frame of", m.name, e);
        }
    }
    return null;
}

/** Keeps [blob] (a moved download's own picture) as [id]'s row picture, shrunk to a data URL. */
export async function setThumbFromBlob(id: string, blob: Blob): Promise<void> {
    const url = await pictureOf(blob);
    if (url) await setThumb(id, url);
}

/** Keeps [url] (an image link or data URL) as [id]'s picture, found now. */
export async function setThumb(id: string, url: string): Promise<void> {
    await db.mediaThumbs.put({
        id,
        url,
        at: Date.now(),
        local: true,
        from: null,
    });
}

const running = new Map<string, Promise<void>>();
let chain: Promise<unknown> = Promise.resolve();

/** Looks for [m]'s picture unless it was found (or looked for lately), one file at a time. */
export function requestThumb(m: MediaFile): Promise<void> {
    // A file that reached this device (or got a Dropbox picture) is looked at again
    const key = `${m.id}:${m.local ? 1 : 0}:${m.thumb ?? ""}`;
    const busy = running.get(key);
    if (busy) return busy;
    // A failure (a full disk, say) must not stop the files after it
    const job = chain
        .then(async () => {
            const known = await db.mediaThumbs.get(m.id);
            if (!shouldTry(known, m)) return;
            const started = Date.now();
            const found = await find(m).catch(
                (): Found => ({ url: null, from: null, sideFailed: false }),
            );
            await db.transaction("rw", db.mediaThumbs, async () => {
                // A picture set meanwhile (a moved download's own) wins over what was found
                const now = await db.mediaThumbs.get(m.id);
                if (now?.url && now.at >= started) return;
                await db.mediaThumbs.put({
                    id: m.id,
                    url: found.url ?? now?.url ?? null,
                    at: Date.now(),
                    local: m.local,
                    from:
                        found.from ?? (found.url ? null : (now?.from ?? null)),
                    sideFailedAt: found.sideFailed ? Date.now() : null,
                });
            });
        })
        .catch(() => undefined);
    chain = job;
    running.set(key, job);
    void job.finally(() => running.delete(key));
    return job;
}

/** The pictures of [files] by id, filled in as they are found (the files in the list order). */
export function useMediaThumbs(files: MediaFile[]): Record<string, string> {
    const key = files
        .map((m) => `${m.id}:${m.local ? 1 : 0}:${m.thumb ?? ""}`)
        .join("|");
    const ids = useMemo(() => files.map((m) => m.id), [key]);
    const rows = useLiveQuery(() => db.mediaThumbs.bulkGet(ids), [ids]);
    useEffect(() => {
        for (const m of files) void requestThumb(m);
    }, [key]);
    return useMemo(() => {
        const out: Record<string, string> = {};
        for (const r of rows ?? []) if (r?.url) out[r.id] = r.url;
        return out;
    }, [rows]);
}
