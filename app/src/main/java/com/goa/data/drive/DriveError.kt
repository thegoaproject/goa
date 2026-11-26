package com.goa.data.drive

/** Why a Drive call failed, in the words the player sees (contracts/drive-layout.md, "Errors mapped for the player"). */
sealed class DriveError(val message: String) {
    data object NoConnection : DriveError("No connection. Nothing was changed; try again when you're online.")
    data object AccessRemoved : DriveError("Google Drive access was removed. Allow it again to continue.")
    data object DriveFull : DriveError("Your Google Drive is full.")
    data object NotResponding : DriveError("Google Drive isn't responding. Try again later.")
    data object NotFound : DriveError("This save state is no longer in Google Drive.")
    data object Damaged : DriveError("The save state from Google Drive was damaged on the way. Nothing was changed.")
    /** Drive's file arrived intact but isn't a save state GOA can load (replaced by hand, or made by another program). */
    data object NotASaveState : DriveError(
        "Drive's copy isn't a save state GOA can load (it may have been replaced or damaged in Drive). Nothing was changed."
    )
    class Other(detail: String) : DriveError("Google Drive: $detail")
}

class DriveException(val error: DriveError, cause: Throwable? = null) : Exception(error.message, cause)
