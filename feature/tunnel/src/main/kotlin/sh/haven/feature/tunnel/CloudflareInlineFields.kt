package sh.haven.feature.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top-level sealed class describing the result of a Cloudflare Tunnel
 * "Test connection" attempt. Lives outside any ViewModel so both the
 * Tunnels screen (`TunnelViewModel`) and the SSH profile editor
 * (`ConnectionsViewModel`) can hand the same state shape to
 * [CloudflareInlineFields].
 */
sealed class CloudflareTunnelTestResult {
    data object Idle : CloudflareTunnelTestResult()
    data object Running : CloudflareTunnelTestResult()
    data class Success(val message: String) : CloudflareTunnelTestResult()
    data class Failure(val message: String) : CloudflareTunnelTestResult()
}

/**
 * Reusable inline fields for a Cloudflare Tunnel transport. Used by:
 *  - the Tunnels screen's Add dialog (legacy standalone tunnels)
 *  - the SSH profile editor (inlined transport on the profile itself,
 *    GH #154 — set [showHostname] = false there, since the SSH "Host"
 *    field doubles as the tunnel hostname).
 *
 * Stateless: caller owns the state and reacts to the callbacks.
 */
@Composable
fun CloudflareInlineFields(
    hostname: String,
    onHostnameChange: (String) -> Unit,
    teamDomain: String,
    onTeamDomainChange: (String) -> Unit,
    jwt: String,
    jwtExpiresAt: Long,
    jumpDestination: String,
    onJumpDestinationChange: (String) -> Unit,
    onSignInClick: () -> Unit,
    advancedOpen: Boolean,
    onAdvancedToggle: () -> Unit,
    onJwtPaste: (jwt: String, expiresAtSeconds: Long) -> Unit,
    testResult: CloudflareTunnelTestResult,
    onTestClick: () -> Unit,
    showHostname: Boolean = true,
    showExperimentBanner: Boolean = true,
    showIntroBlurb: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showExperimentBanner) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    "Experimental — Haven implements the same WebSocket wire protocol as `cloudflared access ssh`. Tested against a small set of tenants; please report mismatches via GitHub #154.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        if (showIntroBlurb) {
            Text(
                "Route SSH through a Cloudflare Tunnel published hostname — the in-app equivalent of `cloudflared access ssh --hostname <host>`. " +
                    "Public Tunnel routes need only a hostname. Access-protected routes additionally require a JWT — tap Sign in to capture one via your team's IdP.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showHostname) {
            OutlinedTextField(
                value = hostname,
                onValueChange = onHostnameChange,
                label = { Text("Hostname (e.g. ssh.example.com)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        val now = remember { System.currentTimeMillis() / 1000 }
        val jwtStatus = when {
            jwt.isBlank() -> "No JWT — only works for unprotected Tunnel routes"
            jwtExpiresAt in 1 until now -> "JWT expired — sign in again"
            jwtExpiresAt > 0 -> {
                val secs = jwtExpiresAt - now
                val hours = secs / 3600
                if (hours > 0) "Signed in · expires in ~${hours}h" else "Signed in · expires in <1h"
            }
            else -> "Signed in"
        }
        Text(
            jwtStatus,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                jwt.isBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                jwtExpiresAt in 1 until now -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )

        OutlinedButton(
            onClick = onTestClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = hostname.isNotBlank() &&
                testResult !is CloudflareTunnelTestResult.Running,
        ) {
            Text(
                if (testResult is CloudflareTunnelTestResult.Running) {
                    "Testing…"
                } else {
                    "Test connection"
                },
            )
        }
        when (testResult) {
            CloudflareTunnelTestResult.Idle,
            CloudflareTunnelTestResult.Running -> Unit
            is CloudflareTunnelTestResult.Success -> {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        testResult.message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            is CloudflareTunnelTestResult.Failure -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        testResult.message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        TextButton(onClick = onAdvancedToggle) {
            Text(if (advancedOpen) "Hide advanced" else "Advanced (Access auth, bastion, paste JWT)")
        }
        if (advancedOpen) {
            OutlinedButton(
                onClick = onSignInClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = hostname.isNotBlank(),
            ) {
                Text(if (jwt.isBlank()) "Sign in via Cloudflare Access" else "Re-authenticate")
            }
            OutlinedTextField(
                value = teamDomain,
                onValueChange = onTeamDomainChange,
                label = { Text("Team domain (optional)") },
                placeholder = {
                    Text(
                        "myteam.cloudflareaccess.com",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    Text(
                        "Only needed for Access-protected routes; lets us capture the team-domain cookie as a fallback if the per-app one is missing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            OutlinedTextField(
                value = jumpDestination,
                onValueChange = onJumpDestinationChange,
                label = { Text("Jump destination (optional)") },
                placeholder = {
                    Text(
                        "internal-host:22",
                        fontFamily = FontFamily.Monospace,
                    )
                },
                supportingText = {
                    Text(
                        "Bastion-mode multi-target tunnels: forwarded as `Cf-Access-Jump-Destination`. Leave blank for one-target routes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                "For headless setups: run `cloudflared access token --app https://<hostname>` on another machine and paste the JWT here. Expiry is parsed from the token automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = jwt,
                onValueChange = { pasted ->
                    val expiry = sh.haven.core.security.JwtPayload.parse(pasted)
                        ?.expiresAtSeconds ?: 0L
                    onJwtPaste(pasted, expiry)
                },
                label = { Text("Cloudflare Access JWT (optional)") },
                singleLine = false,
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}
