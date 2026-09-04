@echo off
REM Windows shim for the Makefile targets (no GNU make required).
setlocal
if "%1"=="" goto all
goto %1
:all
call :generate && call :build && call :train && call :evaluate
goto :eof
:generate
python -m datagen --out data && python -m datagen --profile train_t1 --out data && python -m datagen --profile train_t2 --out data && python -m datagen --profile train_t3 --out data && python verify_data.py data/merchant_a data/merchant_b_ood
goto :eof
:build
if not exist engine\out mkdir engine\out
dir /s /b engine\src\*.java > %TEMP%\siq_srcs.txt
javac -d engine/out @%TEMP%\siq_srcs.txt
goto :eof
:train
for %%m in (train_t1 train_t2 train_t3 merchant_a) do java -cp engine/out com.settleiq.Main --merchant data/%%m --preset full --out reports/%%m --audit reports/tmp_%%m.jsonl --dump-candidates reports/%%m/candidates.csv > nul
python mlservice/train.py
goto :eof
:evaluate
if exist reports\audit_ledger.jsonl del reports\audit_ledger.jsonl
java -cp engine/out com.settleiq.Main --merchant data/merchant_a --preset full --out reports
python evaluator/evaluate.py data/merchant_a full reports/full
python evaluator/ablate.py data/merchant_a
python evaluator/curves.py data/merchant_a reports/full
goto :eof
:demo
python demo.py
goto :eof
:serve
if exist reports\audit_ledger.jsonl del reports\audit_ledger.jsonl
java -cp engine/out com.settleiq.Main --merchant data/merchant_a --preset full --out reports --serve 8733
goto :eof
:verify
python verify_data.py data/merchant_a data/merchant_b_ood
goto :eof
:clean
if exist engine\out rmdir /s /q engine\out
if exist reports rmdir /s /q reports
if exist data rmdir /s /q data
goto :eof
