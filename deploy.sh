#!/bin/bash

# Git Developer Contribution - Deployment Script
# Run this script on your EC2 instance to deploy/update the application

set -e

echo "🚀 Starting deployment..."

# Navigate to project directory
cd /home/ec2-user/Space-Developer-Contribution 2>/dev/null || cd ~/Space-Developer-Contribution

# Pull latest changes
echo "📥 Pulling latest changes from Git..."
git pull origin main

# Build the application
echo "🔨 Building application..."
./gradlew build -x test --no-daemon

# Restart the service
echo "🔄 Restarting service..."
sudo systemctl restart gitcontribution 2>/dev/null || echo "Service not configured, running manually..."

# Check status
if systemctl is-active --quiet gitcontribution 2>/dev/null; then
    echo "✅ Service is running!"
    sudo systemctl status gitcontribution --no-pager
else
    echo "⚠️  Service not configured. To run manually:"
    echo "   ./gradlew bootRun --args='--server.port=8081'"
fi

echo ""
echo "🎉 Deployment complete!"
echo "📍 Access your app at: http://$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo '<your-ip>'):8081"

