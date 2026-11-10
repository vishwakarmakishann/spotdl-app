# spotdl Cloud Backend Server

A high-performance Python/FastAPI service for offloading music audio stream extraction, lossless remuxing, metadata injection, and synced lyrics fetching.

---

## 1. Quick Deploy to Render (100% Free)

1. Fork or push this repository to your GitHub account.
2. Sign in to [Render.com](https://render.com).
3. Click **New +** → **Web Service**.
4. Connect your GitHub repository.
5. Select **Docker** as the runtime (Render will automatically detect `server/Dockerfile`).
6. Set the **Root Directory** to `server`.
7. Choose the **Free** instance type and click **Create Web Service**.
8. Render will deploy your service and give you a public URL (e.g., `https://spotdl-backend-xxxx.onrender.com`).
9. Copy this URL into the **spotdl Android app** under **Settings → Remote Server Engine → Server URL** and tap **Ping**.

---

## 2. Running Locally on your Laptop

To run the server on your local machine and connect from your phone over Wi-Fi:

```bash
cd server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

Then in the Android app:
- Set Server URL to `http://<YOUR_LAPTOP_LOCAL_IP>:8000` (e.g. `http://192.168.1.15:8000`).
- Tap **Ping** to verify the connection.

