import { ApiError } from '@paywallet/core'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { FeedbackProvider, actionError, usePin } from './feedback'

beforeAll(() => {
  // jsdom has no <dialog> modal support.
  HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
    this.setAttribute('open', '')
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.removeAttribute('open')
  })
})

function Pay({ run }: { run: (pin: string) => Promise<string> }) {
  const withPin = usePin()
  const [outcome, setOutcome] = useState('')
  return (
    <>
      <button onClick={() => withPin({ title: 'Confirm', run }).then(setOutcome, (e) => setOutcome(actionError(e) ?? 'cancelled'))}>
        Pay
      </button>
      <p data-testid="outcome">{outcome}</p>
    </>
  )
}

function renderWith(run: (pin: string) => Promise<string>) {
  const router = createMemoryRouter([{ path: '/', element: <FeedbackProvider><Pay run={run} /></FeedbackProvider> }])
  render(<RouterProvider router={router} />)
}

describe('PIN dialog', () => {
  it('keeps the dialog open after a wrong PIN and completes with the right one', async () => {
    const run = vi.fn(async (pin: string) => {
      if (pin !== '482915') throw new ApiError(403, 'Invalid transaction PIN')
      return 'paid'
    })
    renderWith(run)
    const user = userEvent.setup()

    await user.click(screen.getByText('Pay'))
    await user.type(screen.getByLabelText(/transaction pin/i), '111111')
    await user.click(screen.getByText('Confirm', { selector: 'button' }))
    expect(await screen.findByText(/wrong pin/i)).toBeInTheDocument()

    await user.type(screen.getByLabelText(/transaction pin/i), '482915')
    await user.click(screen.getByText('Confirm', { selector: 'button' }))

    expect(await screen.findByText('paid')).toBeInTheDocument()
    expect(run).toHaveBeenCalledTimes(2)
  })

  it('passes other errors to the caller and treats closing as a cancellation', async () => {
    renderWith(async () => {
      throw new ApiError(422, 'Insufficient balance')
    })
    const user = userEvent.setup()

    await user.click(screen.getByText('Pay'))
    await user.type(screen.getByLabelText(/transaction pin/i), '482915')
    await user.click(screen.getByText('Confirm', { selector: 'button' }))
    expect(await screen.findByText('Insufficient balance')).toBeInTheDocument()

    await user.click(screen.getByText('Pay'))
    await user.click(screen.getByLabelText('Close'))
    expect(await screen.findByText('cancelled')).toBeInTheDocument()
  })
})
