// Types for the parts of foliate-js that Feeder uses (the library itself is plain JavaScript).

export interface TocItem {
  label: string
  href: string
  subitems?: TocItem[]
}

export interface FoliateBook {
  metadata?: {
    title?: string | Record<string, string>
    author?: unknown
    language?: string | string[]
  }
  toc?: TocItem[]
  sections: { linear?: string; cfi?: string; size: number; createDocument?: () => Promise<Document> }[]
  dir?: string
  getCover?: () => Promise<Blob | null>
  transformTarget?: EventTarget
  rendition?: { layout?: string }
}

export interface Location {
  fraction: number
  cfi: string
  range: Range
  tocItem?: { label?: string; href?: string } | null
  location?: { current: number; next: number; total: number }
  section?: { current: number; total: number }
  time?: { section: number; total: number }
}

export interface Renderer extends HTMLElement {
  /** Feeder: touches write (Draw on page) instead of turning pages. */
  noSwipe?: boolean
  getContents(): { doc: Document; index: number; overlayer?: unknown }[]
  next(): Promise<void>
  prev(): Promise<void>
  // Reflowable books only (paginator); fixed layouts (PDF, comics) lack these
  setStyles?(css: string | [string, string]): void
  scrollToAnchor?(anchor: Range | Element | number, select?: boolean): Promise<void>
  nextSection?(): Promise<void>
  prevSection?(): Promise<void>
  readonly atEnd?: boolean
  readonly atStart?: boolean
}

export interface Annotation {
  value: string
  color?: string
  [k: string]: unknown
}

export declare class FoliateView extends HTMLElement {
  book: FoliateBook
  renderer: Renderer
  lastLocation: Location | null
  isFixedLayout: boolean
  open(book: File | Blob | string | FoliateBook): Promise<void>
  close(): void
  init(opts: { lastLocation?: string | null; showTextStart?: boolean }): Promise<void>
  goTo(target: string | number): Promise<unknown>
  goToFraction(f: number): Promise<void>
  goLeft(): Promise<void>
  goRight(): Promise<void>
  next(): Promise<void>
  prev(): Promise<void>
  getCFI(index: number, range?: Range): string
  resolveNavigation(target: string | number): { index: number; anchor?: (doc: Document) => Range | Element | null } | undefined
  addAnnotation(a: Annotation, remove?: boolean): Promise<{ index: number; label: string } | undefined>
  deleteAnnotation(a: Annotation): Promise<unknown>
  showAnnotation(a: Annotation): Promise<void>
  deselect(): void
  getSectionFractions(): number[]
  getProgressOf(index: number, range?: Range): { tocItem?: { label?: string; href?: string } | null }
}

export declare const makeBook: (file: File | Blob | string) => Promise<FoliateBook>
export declare class ResponseError extends Error {}
export declare class NotFoundError extends Error {}
export declare class UnsupportedTypeError extends Error {}
