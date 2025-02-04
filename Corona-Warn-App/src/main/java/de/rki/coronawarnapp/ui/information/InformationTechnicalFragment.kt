package de.rki.coronawarnapp.ui.information

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.fragment.app.Fragment
import de.rki.coronawarnapp.R
import de.rki.coronawarnapp.databinding.FragmentInformationTechnicalBinding
import de.rki.coronawarnapp.util.ui.popBackStack
import de.rki.coronawarnapp.util.ui.viewBinding

/**
 * Basic Fragment which only displays static content.
 */
class InformationTechnicalFragment : Fragment(R.layout.fragment_information_technical) {
    companion object {
        private val TAG: String? = InformationTechnicalFragment::class.simpleName
    }

    private val binding: FragmentInformationTechnicalBinding by viewBinding()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { popBackStack() }
    }

    override fun onResume() {
        super.onResume()
        binding.informationTechnicalContainer.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }
}
