/** A button on a toast, such as Undo. */
export interface ToastAction {
  label: string
  run: () => void
}

export type Toast = (msg: string, action?: ToastAction) => void
