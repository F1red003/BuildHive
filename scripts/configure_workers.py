"""Create local Compose worker credentials without printing or replacing secrets."""
import secrets
from pathlib import Path

if __name__ == "__main__":
    target = Path(__file__).resolve().parents[1] / ".env"
    with target.open("x", encoding="utf-8") as stream:
        for number in (1, 2):
            stream.write(f"BUILDHIVE_WORKER_{number}_KEY={secrets.token_hex(32)}\n")
    print("Created .env with distinct worker credentials. Keep this file private.")
