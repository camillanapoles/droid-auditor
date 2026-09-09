package com.camillanapoles.droidauditor.data.findings

/**
 * Resolutive diagnosis engine: maps a crash/ANR stack trace to a root cause
 * and an actionable resolution (executable command template or manual steps).
 *
 * Rules are ordered; first match wins. Every rule states cause + fix, never
 * just a label — "erros e crashes de logs tenham diagnostico resolutivo".
 */
object CrashDiagnoser {

    data class Diagnosis(
        val title: String,        // o que aconteceu
        val cause: String,        // causa raiz
        val resolution: String,   // como resolver (passo concreto)
        val command: String?,     // comando executavel com <pkg>, ou null
        val requiresRoot: Boolean,
        val severity: String      // severidade do finding
    )

    private class Rule(
        val pattern: Regex,
        val build: () -> Diagnosis
    )

    private val RULES = listOf(
        Rule(Regex("java\\.lang\\.OutOfMemoryError")) {
            Diagnosis(
                title = "App esgotou a memoria (limite de heap)",
                cause = "O processo alcancou o limite maximo de heap do Android e o GC nao conseguiu liberar memoria.",
                resolution = "1) Force stop e limpe o cache do app (cache corrompido/cheio piora o consumo). " +
                    "2) Se repetir, reduza o uso simultaneo ou atualize o app (vazamento de memoria e bug do app).",
                command = "rm -rf /data/data/<pkg>/cache/* && am force-stop <pkg>",
                requiresRoot = true,
                severity = "HIGH"
            )
        },
        Rule(Regex("SQLiteDiskIOException|SQLiteCantOpenDatabaseException|database disk image is malformed|SQLiteFullException")) {
            Diagnosis(
                title = "Banco de dados local corrompido ou sem espaco",
                cause = "O SQLite do app esta corrompido (kill durante escrita) ou o disco encheu.",
                resolution = "1) Libere espaco (armazenamento quase cheio corrompe gravacoes). " +
                    "2) Limpar os DADOS do app recria o banco (ATENCAO: apaga config/login do app — exporte backups antes).",
                command = "pm clear <pkg>",
                requiresRoot = true,
                severity = "CRITICAL"
            )
        },
        Rule(Regex("Input dispatching timed out|Broadcast of Intent|executing service timed out")) {
            Diagnosis(
                title = "ANR — app travou a thread principal",
                cause = "A UI ficou sem responder >5s (espera em disco/rede na thread principal).",
                resolution = "1) Force stop resolve o travamento imediato. " +
                    "2) ANR recorrente e bug do app: atualize ou reinstale; se for pos-atualizacao, limpe cache.",
                command = "am force-stop <pkg>",
                requiresRoot = true,
                severity = "HIGH"
            )
        },
        Rule(Regex("java\\.lang\\.SecurityException")) {
            Diagnosis(
                title = "Permissao negada no momento do uso",
                cause = "O app tentou acessar um recurso protegido sem a permissao concedida (ou ela foi revogada).",
                resolution = "Conceda a permissao citada na mensagem em Configuracoes > Apps > <app> > Permissoes, " +
                    "ou revogue e reconceda para reinicializar o estado. Force stop apos conceder.",
                command = "am force-stop <pkg>",
                requiresRoot = true,
                severity = "MEDIUM"
            )
        },
        Rule(Regex("java\\.lang\\.ClassNotFoundException|NoSuchMethodError|Resources\\\$NotFoundException|InflateException")) {
            Diagnosis(
                title = "Instalacao/atualizacao incompleta ou incompativel",
                cause = "Classe/recurso esperado nao existe no APK instalado — atualizacao parcial, APK antigo sobre dados novos ou dex corrompido.",
                resolution = "Reinstale/reatualize o APK por cima (adb install -r ou loja). " +
                    "Se persistir, limpe dados do app (o estado antigo pode exigir recursos removidos).",
                command = "am force-stop <pkg>",
                requiresRoot = true,
                severity = "HIGH"
            )
        },
        Rule(Regex("ForegroundServiceDidNotStartInTime|did not then call Service\\.startForeground")) {
            Diagnosis(
                title = "Servico em primeiro plano nao iniciou a tempo",
                cause = "O app chamou startForegroundService mas nao exibiu a notificacao em < 5s — geralmente after kill agressivo de bateria.",
                resolution = "Force stop o app. Se recorrente, conceda excecao de otimizacao de bateria ao app " +
                    "(Configuracoes > Apps > Bateria > Sem restricoes).",
                command = "am force-stop <pkg>",
                requiresRoot = true,
                severity = "MEDIUM"
            )
        },
        Rule(Regex("CertPathValidatorException|SSLException|TrustAnchorNotFoundException|Handshake failed")) {
            Diagnosis(
                title = "Falha na validacao de certificado (TLS)",
                cause = "Certificado do servidor rejeitado: data/hora errada, CA de usuario ausente, ou rede interceptando (proxy/pi-hole mal configurado).",
                resolution = "1) Confira data e hora do aparelho (automatico). " +
                    "2) Se a rede exige CA propria, instale a CA em Configuracoes > Seguranca > Instalar certificado. " +
                    "3) Teste em outra rede para isolar interceptacao.",
                command = null,
                requiresRoot = false,
                severity = "MEDIUM"
            )
        },
        Rule(Regex("TransactionTooLargeException|DeadObjectException|BinderProxy")) {
            Diagnosis(
                title = "Limite do Binder excedido entre processos",
                cause = "O app tentou transmitir um payload grande demais via IPC (Intent/Bundle) ou o processo remoto morreu no meio da chamada.",
                resolution = "Force stop dos apps envolvidos limpa o binder quebrado. " +
                    "Recorrente e bug do app (payload grande) — atualize.",
                command = "am force-stop <pkg>",
                requiresRoot = true,
                severity = "MEDIUM"
            )
        },
        Rule(Regex("No space left on device|ENOSPC")) {
            Diagnosis(
                title = "Armazenamento cheio",
                cause = "A particao /data nao tem espaco para o app escrever.",
                resolution = "Libere espaco: limpar caches do sistema resolve o travamento imediato; " +
                    "depois remova arquivos/apps grandes.",
                command = "pm trim-caches 999999999999",
                requiresRoot = true,
                severity = "CRITICAL"
            )
        },
        Rule(Regex("IllegalStateException|NullPointerException")) {
            Diagnosis(
                title = "Estado interno invalido do app",
                cause = "Bug de programacao (estado nulo/invalido acessado). Geralmente disparado por um caso de uso especifico.",
                resolution = "1) Force stop e reabra. 2) Limpe o cache. " +
                    "3) Recorrente: reporte ao desenvolvedor com o log (o caminho do log bruto esta salvo neste evento).",
                command = "rm -rf /data/data/<pkg>/cache/* && am force-stop <pkg>",
                requiresRoot = true,
                severity = "HIGH"
            )
        }
    )

    private val FALLBACK = Diagnosis(
        title = "Falha nao catalogada",
        cause = "Nenhuma regra de diagnostico casou com este stack trace.",
        resolution = "Force stop e reabra o app. Se persistir, reporte ao desenvolvedor anexando o log bruto " +
            "(caminho salvo neste evento).",
        command = "am force-stop <pkg>",
        requiresRoot = true,
        severity = "HIGH"
    )

    /** First matching diagnosis for a raw crash/ANR text block. */
    fun diagnose(crashText: String): Diagnosis {
        val matched = RULES.firstOrNull { it.pattern.containsMatchIn(crashText) }
        return matched?.build?.invoke() ?: FALLBACK
    }
}
