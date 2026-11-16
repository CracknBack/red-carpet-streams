// Sends the licence-key email through Resend. If Resend isn't configured yet (no RESEND_API_KEY),
// it quietly does nothing: the buyer still gets the key on the thank-you page.
import { SUPPORT_EMAIL, MAX_DEVICES } from './license.mjs';

export async function sendKeyEmail(to, key) {
  const apiKey = process.env.RESEND_API_KEY;
  const from = process.env.LICENSE_EMAIL_FROM; // e.g. "Red Carpet Player <keys@redcarpetplayer.store>"
  if (!apiKey || !from || !to) return false;
  const text =
`Thank you for buying Red Carpet Pro!

Your license key:

    ${key}

To activate: open Red Carpet Player, go to Settings, choose "Enter a Red Carpet Pro license key", and type or paste the key above.
The key works on up to ${MAX_DEVICES} of your devices. Keep this email somewhere safe.

Need help? Reply to this email or write to ${SUPPORT_EMAIL}.
`;
  const html =
`<p>Thank you for buying <b>Red Carpet Pro</b>!</p>
<p>Your license key:</p>
<p style="font-size:22px;font-family:monospace;letter-spacing:1px"><b>${key}</b></p>
<p>To activate: open Red Carpet Player, go to <b>Settings</b>, choose <b>Enter a Red Carpet Pro license key</b>, and type or paste the key above.<br>
The key works on up to ${MAX_DEVICES} of your devices. Keep this email somewhere safe.</p>
<p>Need help? Reply to this email or write to ${SUPPORT_EMAIL}.</p>`;
  try {
    const res = await fetch('https://api.resend.com/emails', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to: [to], reply_to: SUPPORT_EMAIL, subject: 'Your Red Carpet Pro license key', text, html }),
    });
    if (!res.ok) console.error('resend failed', res.status);
    return res.ok;
  } catch (e) { console.error('resend error', e && e.message); return false; }
}
