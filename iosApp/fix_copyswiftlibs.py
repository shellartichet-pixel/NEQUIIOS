#!/usr/bin/env python3
"""
Script para eliminar CopySwiftLibs build phases de targets _Privacy
Esto corrige el error de build donde CopySwiftLibs intenta copiar un ejecutable que no existe
"""
import re
import sys

try:
    with open('Pods/Pods.xcodeproj/project.pbxproj', 'r') as f:
        content = f.read()
    
    original_content = content
    
    # Estrategia más simple: encontrar todas las referencias a CopySwiftLibs en buildPhases
    # y eliminarlas, luego comentar las definiciones
    
    # 1. Encontrar todos los UUIDs de CopySwiftLibs phases
    copy_swift_libs_pattern = r'([A-F0-9]{24}) /\* CopySwiftLibs \*/ = \{'
    copy_swift_libs_phases = re.findall(copy_swift_libs_pattern, content)
    
    if copy_swift_libs_phases:
        print(f"  Encontrados {len(copy_swift_libs_phases)} CopySwiftLibs phases")
        
        # 2. Eliminar referencias en buildPhases de todos los targets
        for phase_uuid in copy_swift_libs_phases:
            # Eliminar la línea completa que contiene la referencia en buildPhases
            # Patrón: espacios + UUID + /* CopySwiftLibs */ + posible coma + newline
            pattern = rf'\s*{phase_uuid} /\* CopySwiftLibs \*/,?\s*\n'
            content = re.sub(pattern, '', content)
            print(f"    Removida referencia a CopySwiftLibs phase {phase_uuid}")
        
        # 3. Comentar las definiciones completas de CopySwiftLibs phases
        for phase_uuid in copy_swift_libs_phases:
            # Buscar la definición completa del phase (hasta el cierre })
            # Patrón: UUID /* CopySwiftLibs */ = { ... };
            phase_def_pattern = rf'({phase_uuid} /\* CopySwiftLibs \*/ = \{{[^}}]+\}};)'
            def_match = re.search(phase_def_pattern, content, re.DOTALL)
            if def_match:
                # Comentar toda la definición
                content = content.replace(def_match.group(0), f'/* {def_match.group(0)} */')
                print(f"    Comentada definición de CopySwiftLibs phase {phase_uuid}")
    
    if content != original_content:
        with open('Pods/Pods.xcodeproj/project.pbxproj', 'w') as f:
            f.write(content)
        print("✅ Fix aplicado correctamente - CopySwiftLibs removido de todos los targets")
    else:
        print("⚠️ No se encontraron CopySwiftLibs phases para remover")
        
except Exception as e:
    print(f"⚠️ Error aplicando fix: {e}")
    import traceback
    traceback.print_exc()
    sys.exit(0)  # No fallar el build

