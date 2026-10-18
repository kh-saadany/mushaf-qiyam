import os
import sys
import json
import urllib.request

def main():
    token = os.environ.get('GH_TOKEN', '')
    sha = os.environ.get('GITHUB_SHA', '')
    repo = os.environ.get('GITHUB_REPOSITORY', '')

    if not token or not sha or not repo:
        print("Missing GH_TOKEN, GITHUB_SHA, or GITHUB_REPOSITORY")
        sys.exit(0)

    log_path = 'compilation_log.txt'
    if not os.path.exists(log_path):
        print(f"{log_path} does not exist")
        sys.exit(0)

    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        text = f.read()

    tail = text[-3000:] if len(text) > 3000 else text

    body = f"### Build Log Tail for commit {sha[:7]} (total length {len(text)}):\n```\n{tail}\n```"

    url = f"https://api.github.com/repos/{repo}/commits/{sha}/comments"
    req = urllib.request.Request(
        url,
        data=json.dumps({"body": body}).encode('utf-8'),
        headers={
            "Authorization": f"Bearer {token}",
            "User-Agent": "Mozilla/5.0",
            "Content-Type": "application/json"
        }
    )

    try:
        with urllib.request.urlopen(req) as resp:
            print("Successfully posted error comment to commit! Code:", resp.status)
    except Exception as e:
        print("Failed to post comment:", e)

if __name__ == '__main__':
    main()
