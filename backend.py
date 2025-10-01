from fastapi import FastAPI, HTTPException, Header
from googleapiclient.discovery import build
from google.oauth2 import service_account
import requests
from datetime import datetime, timedelta, timezone

app = FastAPI()

# Configuration - Replace these with your actual values
PACKAGE_NAME = "com.example.yourapp"  # Your Google Play package name
SUBSCRIPTION_ID = "your_subscription_id"  # Your subscription product ID
SERVICE_ACCOUNT_FILE = "path/to/your/service_account.json"  # Path to your Google Service Account JSON key file
TINFOIL_ADMIN_KEY = "your_tinfoil_admin_key_here"  # Your Tinfoil admin API key (keep this secure!)
TINFOIL_API_URL = "https://api.tinfoil.sh/api/keys"

# Load Google API credentials (do this once)
credentials = service_account.Credentials.from_service_account_file(
    SERVICE_ACCOUNT_FILE,
    scopes=['https://www.googleapis.com/auth/androidpublisher']
)
android_publisher = build('androidpublisher', 'v3', credentials=credentials)

@app.post("/verify-purchase")
async def verify_purchase(authorization: str = Header(..., alias="Authorization")):
    # Extract the purchase token from the Authorization header (expecting Bearer <token>)
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Invalid authorization header")
    
    purchase_token = authorization.split(" ")[1]
    
    try:
        # Verify the purchase with Google Play Developer API
        # This checks if the subscription is valid (e.g., not expired)
        result = android_publisher.purchases().subscriptions().get(
            packageName=PACKAGE_NAME,
            subscriptionId=SUBSCRIPTION_ID,
            token=purchase_token
        ).execute()
        
        # Simple check: if we got here without exception, assume valid
        # In production, check result['expiryTimeMillis'] > current time, etc.
        if 'expiryTimeMillis' in result and int(result['expiryTimeMillis']) > int(datetime.now().timestamp() * 1000):
            # Create temporary Tinfoil API key expiring in 3 minutes
            expires_at = (datetime.now(timezone.utc) + timedelta(minutes=3)).isoformat()
            
            payload = {
                "name": f"Temp Key for Purchase {purchase_token[:10]}",
                "expires_at": expires_at,
                "max_tokens": 10000,  # Small limit for temp key
                "metadata": {
                    "purchase_token": purchase_token[:50]  # Truncate if too long
                }
            }
            
            headers = {
                "Authorization": f"Bearer {TINFOIL_ADMIN_KEY}",
                "Content-Type": "application/json"
            }
            
            response = requests.post(TINFOIL_API_URL, json=payload, headers=headers)
            
            if response.status_code == 200:
                tinfoil_data = response.json()
                return {
                    "success": True,
                    "temp_api_key": tinfoil_data["key"]
                }
            else:
                raise HTTPException(status_code=500, detail="Failed to create temp API key")
        else:
            raise HTTPException(status_code=403, detail="Subscription expired or invalid")
            
    except Exception as e:
        # Catch Google API errors or others
        raise HTTPException(status_code=403, detail="Purchase verification failed")

# To run: uvicorn main:app --reload (save this as main.py)
# Install dependencies: pip install fastapi uvicorn google-api-python-client google-auth requests
