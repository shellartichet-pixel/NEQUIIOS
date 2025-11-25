#!/usr/bin/env python3
"""
Script para eliminar CopySwiftLibs de targets _Privacy en Pods.xcodeproj
Estos bundles no tienen ejecutables, por lo que CopySwiftLibs falla
"""
import re
import sys

def remove_copyswiftlibs_from_privacy_targets(pbxproj_path):
    """Elimina CopySwiftLibs de todos los targets _Privacy"""
    try:
        with open(pbxproj_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        original_content = content
        
        # Patrón 1: Eliminar build phases de CopySwiftLibs para targets _Privacy
        # Buscar secciones que contengan "_Privacy" y "CopySwiftLibs" o "swiftStdLibTool"
        lines = content.split('\n')
        new_lines = []
        skip_next_n_lines = 0
        in_privacy_target = False
        in_copyswiftlibs_phase = False
        brace_count = 0
        
        i = 0
        while i < len(lines):
            line = lines[i]
            
            # Detectar si estamos en un target _Privacy
            if '_Privacy' in line and 'isa = PBXNativeTarget' in lines[max(0, i-5):i+1]:
                in_privacy_target = True
                new_lines.append(line)
                i += 1
                continue
            
            # Si estamos en un target _Privacy, buscar CopySwiftLibs
            if in_privacy_target:
                # Detectar inicio de buildPhases
                if 'buildPhases = (' in line:
                    brace_count = line.count('(') - line.count(')')
                    new_lines.append(line)
                    i += 1
                    continue
                
                # Detectar CopySwiftLibs build phase
                if 'CopySwiftLibs' in line or 'swiftStdLibTool' in line:
                    in_copyswiftlibs_phase = True
                    brace_count = 0
                    # No agregar esta línea, saltarla
                    i += 1
                    continue
                
                # Si estamos en un CopySwiftLibs phase, saltar hasta el final
                if in_copyswiftlibs_phase:
                    brace_count += line.count('(') - line.count(')')
                    if brace_count <= 0 and ');' in line:
                        in_copyswiftlibs_phase = False
                        # Saltar la línea de cierre también
                        i += 1
                        continue
                    i += 1
                    continue
                
                # Si encontramos el final del target, resetear
                if '};' in line and in_privacy_target and not in_copyswiftlibs_phase:
                    in_privacy_target = False
            
            new_lines.append(line)
            i += 1
        
        content = '\n'.join(new_lines)
        
        # Patrón 2: Eliminar referencias a CopySwiftLibs en secciones de buildPhases
        # Buscar y eliminar líneas que contengan CopySwiftLibs y _Privacy
        content = re.sub(r'^\s*[A-F0-9]+\s*/\* CopySwiftLibs.*_Privacy.*\*/,\s*$', '', content, flags=re.MULTILINE)
        content = re.sub(r'^\s*[A-F0-9]+\s*/\* CopySwiftLibs.*Privacy.*\*/,\s*$', '', content, flags=re.MULTILINE)
        
        # Patrón 3: Eliminar referencias a swiftStdLibTool para _Privacy
        content = re.sub(r'builtin-swiftStdLibTool.*nanopb_Privacy.*\n', '', content)
        content = re.sub(r'builtin-swiftStdLibTool.*leveldb_Privacy.*\n', '', content)
        content = re.sub(r'CopySwiftLibs.*_Privacy\.bundle.*\n', '', content)
        
        # Limpiar líneas vacías múltiples
        content = re.sub(r'\n\n\n+', '\n\n', content)
        
        if content != original_content:
            with open(pbxproj_path, 'w', encoding='utf-8') as f:
                f.write(content)
            print(f"✅ Eliminado CopySwiftLibs de targets _Privacy en {pbxproj_path}")
            return True
        else:
            print(f"⚠️ No se encontraron cambios necesarios en {pbxproj_path}")
            return False
            
    except Exception as e:
        print(f"❌ Error procesando {pbxproj_path}: {e}")
        return False

if __name__ == '__main__':
    pbxproj_path = 'Pods/Pods.xcodeproj/project.pbxproj'
    if len(sys.argv) > 1:
        pbxproj_path = sys.argv[1]
    
    success = remove_copyswiftlibs_from_privacy_targets(pbxproj_path)
    sys.exit(0 if success else 1)
