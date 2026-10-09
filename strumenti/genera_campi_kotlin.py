#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Genera le costanti Kotlin F_* dei campi wellness da python/campi.py (elenco unico).
08/10/2026. Uso (da python/): PYTHONPATH=. python3 ../strumenti/genera_campi_kotlin.py <package> > CampiWellness.kt
Non modificare il file generato a mano: si rigenera."""
import sys

import campi


def kotlin(package):
    righe = [f"package {package}", "",
             "// GENERATO da strumenti/genera_campi_kotlin.py (fonte: python/campi.py). Non modificare a mano.",
             "object CampiWellness {"]
    for c in campi.CAMPI:
        righe.append(f'    const val {c["costante"]} = "{c["code"]}"   // {c["nome"]} ({c["units"]})')
    righe.append("    val TUTTI = listOf(" + ", ".join(c["costante"] for c in campi.CAMPI) + ")")
    righe.append("}")
    return "\n".join(righe) + "\n"


if __name__ == "__main__":
    print(kotlin(sys.argv[1] if len(sys.argv) > 1 else "com.noctalix.app.intervals"), end="")
