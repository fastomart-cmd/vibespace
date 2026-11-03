import type { Config } from '../config.js';

export interface MailMessage {
  to: string;
  subject: string;
  text: string;
  html?: string;
}

/** Pluggable mailer. Add a new provider by implementing this interface and wiring it in createMailer(). */
export interface Mailer {
  send(msg: MailMessage): Promise<void>;
}

/** Dev mailer: prints the email (including reset links) to the server log. */
export class ConsoleMailer implements Mailer {
  constructor(private log: (obj: object, msg: string) => void = (o, m) => console.log(m, o)) {}
  async send(msg: MailMessage) {
    this.log({ to: msg.to, subject: msg.subject, text: msg.text }, '[mailer:console] email not sent, logged instead');
  }
}

/** Collects emails in memory. Used by the test suite to read reset tokens. */
export class MemoryMailer implements Mailer {
  sent: MailMessage[] = [];
  async send(msg: MailMessage) {
    this.sent.push(msg);
  }
}

/** Resend (https://resend.com) HTTP API. Implemented but NOT verified in the sandbox (needs an API key). */
export class ResendMailer implements Mailer {
  constructor(private apiKey: string, private from: string) {}
  async send(msg: MailMessage) {
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${this.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from: this.from, to: [msg.to], subject: msg.subject, text: msg.text, html: msg.html }),
    });
    if (!res.ok) throw new Error(`Resend failed: ${res.status} ${await res.text()}`);
  }
}

export function createMailer(config: Config, log?: (obj: object, msg: string) => void): Mailer {
  const kind = config.MAILER ?? (config.RESEND_API_KEY ? 'resend' : 'console');
  if (kind === 'resend') {
    if (!config.RESEND_API_KEY) throw new Error('MAILER=resend requires RESEND_API_KEY');
    return new ResendMailer(config.RESEND_API_KEY, config.MAIL_FROM);
  }
  return new ConsoleMailer(log);
}
