import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { Alert } from '../components/ui'
import { Logo } from '../layout/AppLayout'
import NotFoundPage from './NotFoundPage'

// Fill in before publishing; the text must be reviewed by counsel.
const COMPANY = '[Company legal name]'
const CNPJ = '[CNPJ]'
const ADDRESS = '[Registered address]'
const PARTNER = '[Banking partner authorized by the Central Bank of Brazil]'
const DPO_EMAIL = '[privacy@company]'
const SUPPORT_EMAIL = '[support@company]'
// Must match app.legal.terms-version on the API, which records it with each sign-up.
const TERMS_VERSION = '2026-09-28'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="text-lg font-semibold">{title}</h2>
      <div className="space-y-2 text-sm leading-relaxed text-slate-700">{children}</div>
    </section>
  )
}

function Terms() {
  return (
    <>
      <Section title="1. Who we are">
        <p>
          PayWallet is offered by {COMPANY}, CNPJ {CNPJ}, {ADDRESS}. Accounts, Pix, bill payments and cards are
          provided through {PARTNER}, which holds the balances in your name.
        </p>
      </Section>
      <Section title="2. Who can open an account">
        <p>Individuals aged 18 or over with a valid CPF, who pass our identity verification. One account per CPF.</p>
      </Section>
      <Section title="3. Your security obligations">
        <p>
          Keep your password, transaction PIN, authenticator app and recovery codes to yourself. We will never ask
          for them by phone, message or email. Report a lost device or suspicious activity to {SUPPORT_EMAIL} right
          away.
        </p>
      </Section>
      <Section title="4. Payments, limits and fees">
        <p>
          Payments are confirmed with your transaction PIN and cannot be undone once settled, except through the
          return and dispute mechanisms described in the app. Daily limits and any fees are shown in the app before
          you confirm.
        </p>
      </Section>
      <Section title="5. Credit">
        <p>
          Loans and credit card limits depend on a credit analysis. The total effective cost (CET), taxes (IOF) and
          every installment are shown before you contract.
        </p>
      </Section>
      <Section title="6. Blocking and closing">
        <p>
          We may block operations to protect you or comply with the law, including anti-money-laundering rules. You
          can close your account in the app once nothing is left to settle. Records of your operations are kept for
          the periods the law requires.
        </p>
      </Section>
      <Section title="7. Governing law">
        <p>These terms follow Brazilian law. Consumer disputes may be brought in your home jurisdiction.</p>
      </Section>
    </>
  )
}

function Privacy() {
  return (
    <>
      <Section title="1. Controller and data protection officer">
        <p>
          {COMPANY}, CNPJ {CNPJ}, is the controller of your personal data under the Brazilian General Data Protection
          Law (LGPD, Law 13,709/2018). Data protection officer: {DPO_EMAIL}.
        </p>
      </Section>
      <Section title="2. Data we collect">
        <p>
          Identification (name, CPF, email, phone), identity documents and selfie for verification, device and
          sign-in information, and the transactions you make.
        </p>
      </Section>
      <Section title="3. Why we use it (legal basis)">
        <ul className="list-disc space-y-1 pl-5">
          <li>Provide the account and payments you ask for (performance of contract).</li>
          <li>Verify identity, prevent fraud and money laundering, and report to authorities (legal obligation).</li>
          <li>Analyze credit when you apply for it (credit protection).</li>
          <li>Keep your account secure, e.g. new-device alerts (legitimate interest).</li>
        </ul>
      </Section>
      <Section title="4. Who we share it with">
        <p>
          {PARTNER}; credit bureaus; the card processor; identity verification providers; and public authorities when
          the law requires. We do not sell personal data.
        </p>
      </Section>
      <Section title="5. How long we keep it">
        <p>
          While your account is open and, after closing, for as long as the law requires, including at least five
          years for transaction records under anti-money-laundering law (Law 9,613/1998).
        </p>
      </Section>
      <Section title="6. Your rights">
        <p>
          You can ask to confirm, access, correct, anonymize, port or delete your data, learn who we share it with, and
          withdraw consent, by writing to {DPO_EMAIL}. Some data must be kept to comply with the law even after a
          request. You may also complain to the National Data Protection Authority (ANPD).
        </p>
      </Section>
      <Section title="7. Security">
        <p>
          Personal documents are encrypted at rest with keys held in a key management service, connections use TLS,
          and payments require a transaction PIN. The app stores no advertising or tracking cookies.
        </p>
      </Section>
    </>
  )
}

const documents: Record<string, { title: string; body: () => ReactNode }> = {
  terms: { title: 'Terms of use', body: Terms },
  privacy: { title: 'Privacy policy', body: Privacy },
}

export default function LegalPage() {
  const { document = '' } = useParams()
  const doc = documents[document]
  if (!doc) return <NotFoundPage />
  const Body = doc.body
  return (
    <div className="mx-auto max-w-3xl px-4 py-10">
      <Link to="/" className="mb-8 inline-block"><Logo /></Link>
      <h1 className="text-3xl font-bold tracking-tight">{doc.title}</h1>
      <p className="mt-1 text-sm text-muted">Version {TERMS_VERSION}</p>
      <div className="my-6">
        <Alert tone="warning">Draft pending legal review. Not yet in force.</Alert>
      </div>
      <div className="space-y-6"><Body /></div>
      <p className="mt-10 text-sm text-muted">
        See also the <Link className="font-semibold text-brand-700" to={document === 'terms' ? '/legal/privacy' : '/legal/terms'}>
          {document === 'terms' ? 'privacy policy' : 'terms of use'}
        </Link>.
      </p>
    </div>
  )
}
