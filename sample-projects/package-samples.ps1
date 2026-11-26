$ErrorActionPreference = 'Stop'
python (Join-Path $PSScriptRoot 'generate_samples.py')
exit $LASTEXITCODE
