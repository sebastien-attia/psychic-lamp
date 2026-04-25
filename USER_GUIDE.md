# Boat App — User Guide

## What is the Boat App?

The Boat App lets you keep a personal list of boats. Each user has their own
collection: you can browse, search, add, edit, and remove entries through a
web browser. There's no installation — open the app in your browser and log in.

## Getting started

1. From the project folder, run `docker compose up`. Wait for every service to
   report **healthy** (about 1–2 minutes the first time).
2. Open your browser at <http://localhost:8080>.
3. Click **Login** and use the demo credentials below.

### Login credentials

- **Username:** `demo`
- **Password:** `demo123`

## How to use the app

### Log in

Click **Login** on the welcome screen, enter your credentials on the Keycloak
page, and you'll be redirected to your boat list.

### View your boats

The home page lists your boats with pagination. Use the **search bar** at the
top to filter by name or description (filtering is case-insensitive).

### Add a boat

Click **New Boat**, fill in the form, and click **Save**:

- **Name** — required, up to 64 characters.
- **Description** — optional, up to 256 characters.

Character counters next to each field show how much room you have left.

### View boat details

Click any boat in the list to open its details page. You'll see the full name,
description, and creation date.

### Edit a boat

On the details page, click **Edit**, change the fields, and click **Save**.
Changes are visible immediately.

### Delete a boat

On the details page, click **Delete**. A confirmation dialog appears — click
**Delete** again to confirm. **This action cannot be undone.**

### Log out

Click your name in the top-right menu and choose **Logout**. You'll be returned
to the welcome page.

## Concurrent edits

If somebody else (or you, in another tab) edits the same boat while your edit
form is open, saving will show a **conflict** message with a **Refresh**
button. Click **Refresh** to reload the latest version, then redo your changes.
This protects you from silently overwriting someone else's work.

## Troubleshooting

| Problem | What to check |
|---------|---------------|
| App doesn't load at <http://localhost:8080> | Run `docker compose ps` — every service must show `healthy`. If not, run `docker compose logs` to see what failed. |
| Login fails or hangs | Check Keycloak is up at <http://localhost:8180>. Restart with `docker compose restart keycloak`. |
| You're suddenly redirected to the login page | Your session expired. Log in again. |
| Search returns no results | Search matches both name and description. Clear the field to see the full list. |

For developer setup, build, test, and deployment instructions, see
[`README.md`](README.md).
