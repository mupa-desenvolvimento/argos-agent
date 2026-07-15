package com.mupa.agent.argos.managers

import java.util.Calendar

/**
 * Desbloqueio de manutenção (offline).
 *
 * Aceita:
 *  - Senha mestre fixa (MASTER_PASSWORD), sempre válida.
 *  - Código de 4 dígitos gerado pela Web a partir da data/hora exibida no
 *    dispositivo. O algoritmo é IDÊNTICO ao de src/lib/maintenance-code.ts.
 *
 * Token (challenge) = yyyyMMddHHmm (minuto), ex: 202608201945.
 */
object MaintenanceUnlock {

    const val MASTER_PASSWORD = "#Mu040816Pa050223\$"
    private const val SECRET = "ARGOS-MNT-v1"

    /** Token canônico para um instante (precisão de minuto). */
    fun challengeNow(cal: Calendar = Calendar.getInstance()): String {
        fun p(n: Int) = n.toString().padStart(2, '0')
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        return "$y${p(mo)}${p(d)}${p(h)}${p(mi)}"
    }

    /** Data/hora legível exibida ao técnico (mesma base do token). */
    fun humanNow(cal: Calendar = Calendar.getInstance()): String {
        fun p(n: Int) = n.toString().padStart(2, '0')
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val mo = cal.get(Calendar.MONTH) + 1
        val y = cal.get(Calendar.YEAR)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        return "${p(d)}/${p(mo)}/$y ${p(h)}:${p(mi)}"
    }

    /** Código de 4 dígitos determinístico (precisa bater com o lib JS da web). */
    fun codeFor(challenge: String): String {
        val s = "$SECRET|$challenge"
        var h = 0L
        for (ch in s) {
            h = (h * 131 + ch.code.toLong()) % 1_000_000_007L
        }
        return (h % 10000L).toString().padStart(4, '0')
    }

    /**
     * Valida a entrada do técnico contra a senha mestre OU o código de 4 dígitos.
     * Aceita o minuto exibido e ±2 minutos de tolerância (caso o técnico demore).
     */
    fun validate(input: String, atMillis: Long = System.currentTimeMillis()): Boolean {
        val v = input.trim()
        if (v.isEmpty()) return false
        if (v == MASTER_PASSWORD) return true
        if (!v.matches(Regex("\\d{4}"))) return false
        for (offset in -2..2) {
            val cal = Calendar.getInstance().apply { timeInMillis = atMillis + offset * 60_000L }
            if (codeFor(challengeNow(cal)) == v) return true
        }
        return false
    }
}
